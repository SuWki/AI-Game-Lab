package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.aigamelab.domain.DesignDocument;
import com.aigamelab.repository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable task-scoped production handoffs. Schema v1 uses canonical sorted JSON. */
@Service
public class ProductionArtifactService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TaskRepository tasks;
    private final RunRepository runs;
    private final DesignDocumentRepository documents;
    public ProductionArtifactService(JdbcTemplate db,ObjectMapper mapper,TaskRepository tasks,RunRepository runs,DesignDocumentRepository documents){
        this.db=db;this.mapper=mapper;this.tasks=tasks;this.runs=runs;this.documents=documents;
    }
    public record Ref(String id,int revision,String hash) {}
    public record Request(long taskId,Long producerRunId,String artifactId,Integer expectedRevision,String kind,JsonNode content,List<Ref> inputRefs) {}

    @Transactional
    public JsonNode save(Request request) {
        tasks.findById(request.taskId()).orElseThrow(()->BusinessException.notFound("Task not found"));
        if(request.producerRunId()!=null&&runs.findById(request.producerRunId()).orElseThrow(()->BusinessException.notFound("Run not found")).taskId()!=request.taskId())
            throw BusinessException.conflict("Producer Run belongs to another task");
        validate(request.kind(),request.content());
        var refs=request.inputRefs()==null?List.<Ref>of():request.inputRefs();
        if(refs.size()>32)throw new IllegalArgumentException("At most 32 input references");
        Set<String> unique=new HashSet<>();
        for(var ref:refs){
            if(ref==null||ref.id()==null||ref.revision()<1||!unique.add(ref.id()+":"+ref.revision()))throw new IllegalArgumentException("Invalid or duplicate input reference");
            var input=get(ref.id(),ref.revision());
            if(input.path("taskId").asLong()!=request.taskId()||!input.path("contentHash").asText().equals(ref.hash()))throw BusinessException.conflict("Input scope or hash mismatch");
        }
        if(request.kind().equals("GameSpec")&&!refs.stream().anyMatch(r->r.id().equals(request.content().path("briefRef").asText())&&get(r.id(),r.revision()).path("kind").asText().equals("ProductionBrief")))throw BusinessException.conflict("GameSpec requires a pinned ProductionBrief input");
        if(request.kind().equals("AcceptanceContract")&&!refs.stream().anyMatch(r->r.hash().equals(request.content().path("specHash").asText())&&get(r.id(),r.revision()).path("kind").asText().equals("GameSpec")))throw BusinessException.conflict("AcceptanceContract requires its pinned GameSpec input");
        if(request.kind().equals("AcceptanceContract")){
            var spec=refs.stream().map(r->get(r.id(),r.revision())).filter(n->n.path("kind").asText().equals("GameSpec")&&n.path("contentHash").asText().equals(request.content().path("specHash").asText())).findFirst().orElseThrow();
            Map<String,JsonNode> expected=new HashMap<>();spec.path("content").path("acceptanceCriteria").forEach(n->expected.put(n.path("id").asText(),n));
            if(expected.size()!=request.content().path("checks").size())throw BusinessException.conflict("Acceptance coverage differs from pinned specification");
            for(var check:request.content().path("checks")){var original=expected.get(check.path("id").asText());if(original==null||!original.path("expected").equals(check.path("expected"))||!original.path("method").equals(check.path("method")))throw BusinessException.conflict("Acceptance meaning differs from pinned specification");}
        }
        String id=request.artifactId();int previous=0;
        if(id!=null){
            var latest=latest(id);previous=latest.path("revision").asInt();
            if(latest.path("taskId").asLong()!=request.taskId()||!latest.path("kind").asText().equals(request.kind()))throw BusinessException.conflict("Artifact scope or kind mismatch");
            // An exact retry is idempotent even when its previous revision is now stale.
            if(latest.path("content").equals(canonical(request.content()))&&latest.path("inputRefs").equals(mapper.valueToTree(refs)))return latest;
            if(request.expectedRevision()==null||request.expectedRevision()!=previous)throw BusinessException.conflict("Expected revision does not match current artifact");
        } else {
            if(request.expectedRevision()!=null&&request.expectedRevision()!=0)throw BusinessException.conflict("New artifact expects revision zero");
            id=UUID.randomUUID().toString();
            // Same task/kind/payload/reference retry reuses the existing immutable revision.
            for(var existing:list(request.taskId()))if(existing.path("kind").asText().equals(request.kind())&&existing.path("content").equals(canonical(request.content()))&&existing.path("inputRefs").equals(mapper.valueToTree(refs)))return existing;
        }
        var envelope=mapper.createObjectNode().put("schemaVersion",1).put("kind",request.kind()).put("artifactId",id).put("revision",previous+1).put("taskId",request.taskId());
        if(request.producerRunId()==null)envelope.putNull("producerRunId");else envelope.put("producerRunId",request.producerRunId());
        envelope.set("inputRefs",mapper.valueToTree(refs));envelope.set("content",canonical(request.content()));
        envelope.put("contentHash",hash(envelope.path("content").toString()));
        var document=documents.insert(new DesignDocument(null,request.producerRunId(),request.taskId(),request.kind()+" "+id+" r"+(previous+1),envelope.path("content").toPrettyString(),null));
        db.update("INSERT INTO production_artifact(artifact_id,revision,task_id,kind,document_id,envelope_json) VALUES (?,?,?,?,?,?)",id,previous+1,request.taskId(),request.kind(),document.id(),envelope.toString());
        return read(envelope.toString());
    }
    public JsonNode get(String id,int revision){return db.query("SELECT envelope_json FROM production_artifact WHERE artifact_id=? AND revision=?",(rs,n)->read(rs.getString(1)),id,revision).stream().findFirst().orElseThrow(()->BusinessException.notFound("Artifact revision not found"));}
    public JsonNode latest(String id){return db.query("SELECT envelope_json FROM production_artifact WHERE artifact_id=? ORDER BY revision DESC LIMIT 1",(rs,n)->read(rs.getString(1)),id).stream().findFirst().orElseThrow(()->BusinessException.notFound("Artifact not found"));}
    public List<JsonNode> list(long taskId){return db.query("SELECT envelope_json FROM production_artifact WHERE task_id=? ORDER BY rowid DESC",(rs,n)->read(rs.getString(1)),taskId);}
    private JsonNode read(String text){try{return mapper.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode canonical(JsonNode node){
        if(node.isObject()){ObjectNode out=mapper.createObjectNode();List<String> keys=new ArrayList<>();node.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String key:keys)out.set(key,canonical(node.get(key)));return out;}
        if(node.isArray()){ArrayNode out=mapper.createArrayNode();node.forEach(n->out.add(canonical(n)));return out;}return node.deepCopy();
    }
    private String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void text(JsonNode n,String field){if(!n.path(field).isTextual()||n.path(field).asText().isBlank())throw new IllegalArgumentException("Required text: "+field);}
    private void array(JsonNode n,String field,boolean nonempty){if(!n.path(field).isArray()||(nonempty&&n.path(field).isEmpty()))throw new IllegalArgumentException("Required array: "+field);}
    private void validate(String kind,JsonNode c){
        if(c==null||!c.isObject()||c.toString().getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException("Content must be an object up to 256 KiB");
        if(!Set.of("ProductionBrief","GameSpec","AcceptanceContract").contains(kind==null?"":kind))throw new IllegalArgumentException("Unsupported artifact kind");
        text(c,"title");
        if(kind.equals("ProductionBrief")){for(String f:List.of("mode","genre","targetPlatform","targetPlayer","scope"))text(c,f);if(!Set.of("NEW","DERIVED","MODIFY").contains(c.path("mode").asText()))throw new IllegalArgumentException("Invalid production mode");return;}
        if(kind.equals("AcceptanceContract")){text(c,"specHash");array(c,"checks",true);Set<String> ids=new HashSet<>();for(var check:c.path("checks")){text(check,"id");text(check,"expected");text(check,"method");if(!ids.add(check.path("id").asText()))throw new IllegalArgumentException("Duplicate check id");}return;}
        for(String f:List.of("genre","targetPlatform","coreLoop","endConditions","restartBehavior"))text(c,f);
        for(String f:List.of("controls","states","contentScope","assetNeeds","requirements","acceptanceCriteria","exclusions","openQuestions"))array(c,f,Set.of("controls","states","requirements","acceptanceCriteria").contains(f));
        if(!c.path("systems").isObject())throw new IllegalArgumentException("systems must be an object");
        Set<String> reqs=new HashSet<>(),checks=new HashSet<>();Map<String,String> owner=new HashMap<>();
        for(var r:c.path("requirements")){text(r,"id");text(r,"description");array(r,"acceptanceIds",true);if(!reqs.add(r.path("id").asText()))throw new IllegalArgumentException("Duplicate requirement id");}
        for(var a:c.path("acceptanceCriteria")){for(String f:List.of("id","requirementId","expected","method"))text(a,f);String id=a.path("id").asText();if(!checks.add(id)||!reqs.contains(a.path("requirementId").asText()))throw new IllegalArgumentException("Invalid acceptance identity or requirement");owner.put(id,a.path("requirementId").asText());}
        Set<String> referenced=new HashSet<>();for(var r:c.path("requirements"))for(var id:r.path("acceptanceIds")){if(!id.isTextual()||!r.path("id").asText().equals(owner.get(id.asText())))throw new IllegalArgumentException("Acceptance reference mismatch");referenced.add(id.asText());}
        if(!referenced.equals(checks))throw new IllegalArgumentException("Unreferenced acceptance criteria");
    }
}
