package com.aigamelab.production;
import com.aigamelab.domain.Task;
import com.aigamelab.repository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="llm.gateway.api-key=")
class ProductionArtifactServiceTest {
    static final Path DB;static{try{DB=Files.createTempFile("agl-production-",".db");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB);}
    @Autowired ProductionArtifactService service;@Autowired TaskRepository tasks;@Autowired ObjectMapper mapper;
    long task;
    @BeforeEach void setup(){task=tasks.insert(new Task(null,"production","build a game","{}",20,true,"ACTIVE",null)).id();}
    ObjectNode brief(){return mapper.createObjectNode().put("title","New game").put("mode","NEW").put("genre","survival").put("targetPlatform","web").put("targetPlayer","casual").put("scope","one playable loop");}
    ProductionArtifactService.Request request(String kind,JsonNode content){return new ProductionArtifactService.Request(task,null,null,null,kind,content,List.of());}
    @Test void immutableRevisionRetryAndOptimisticConflict(){
        var b=brief();var first=service.save(request("ProductionBrief",b));assertThat(service.save(request("ProductionBrief",b))).isEqualTo(first);
        String id=first.path("artifactId").asText();b.put("title","Changed");
        var next=service.save(new ProductionArtifactService.Request(task,null,id,1,"ProductionBrief",b,List.of()));assertThat(next.path("revision").asInt()).isEqualTo(2);
        assertThat(service.get(id,1).path("content").path("title").asText()).isEqualTo("New game");
        b.put("title","Other");assertThatThrownBy(()->service.save(new ProductionArtifactService.Request(task,null,id,1,"ProductionBrief",b,List.of()))).hasMessageContaining("revision");
    }
    @Test void canonicalObjectOrderHasSameHash(){
        var b=brief();var first=service.save(request("ProductionBrief",b));ObjectNode reversed=mapper.createObjectNode();List<String> keys=new ArrayList<>();b.fieldNames().forEachRemaining(keys::add);Collections.reverse(keys);keys.forEach(k->reversed.set(k,b.get(k)));
        assertThat(service.save(request("ProductionBrief",reversed))).isEqualTo(first);
    }
    @Test void foreignTaskAndWrongHashCannotBecomeInputs(){
        var first=service.save(request("ProductionBrief",brief()));String id=first.path("artifactId").asText();
        assertThatThrownBy(()->service.save(new ProductionArtifactService.Request(task,null,null,null,"ProductionBrief",brief(),List.of(new ProductionArtifactService.Ref(id,1,"wrong"))))).hasMessageContaining("hash");
        task=tasks.insert(new Task(null,"other","other","{}",20,true,"ACTIVE",null)).id();
        assertThatThrownBy(()->service.save(new ProductionArtifactService.Request(task,null,null,null,"ProductionBrief",brief(),List.of(new ProductionArtifactService.Ref(id,1,first.path("contentHash").asText()))))).hasMessageContaining("scope");
    }
    ObjectNode spec(String briefId)throws Exception{return ((ObjectNode)mapper.readTree("""
        {"title":"Heat survival","genre":"survival","targetPlatform":"web","coreLoop":"move and manage heat","endConditions":"survive","restartBehavior":"reset run","systems":{},"controls":["move"],"states":["play","end"],"contentScope":[],"assetNeeds":[],"exclusions":[],"openQuestions":[],"requirements":[{"id":"R1","description":"restart resets score","acceptanceIds":["A1"]}],"acceptanceCriteria":[{"id":"A1","requirementId":"R1","expected":"score zero","method":"browser"}]}
        """)).set("briefRef",mapper.getNodeFactory().textNode(briefId));}
    @Test void specAndAcceptanceHavePinnedInputChain()throws Exception{
        var b=service.save(request("ProductionBrief",brief()));String id=b.path("artifactId").asText();var content=spec(id);
        var s=service.save(new ProductionArtifactService.Request(task,null,null,null,"GameSpec",content,List.of(new ProductionArtifactService.Ref(id,1,b.path("contentHash").asText()))));
        var acceptance=mapper.createObjectNode().put("title","Acceptance").put("specHash",s.path("contentHash").asText());acceptance.putArray("checks").addObject().put("id","A1").put("expected","score zero").put("method","browser");
        assertThat(service.save(new ProductionArtifactService.Request(task,null,null,null,"AcceptanceContract",acceptance,List.of(new ProductionArtifactService.Ref(s.path("artifactId").asText(),1,s.path("contentHash").asText())))).path("revision").asInt()).isEqualTo(1);
        ((ObjectNode)acceptance.path("checks").get(0)).put("expected","always pass");
        assertThatThrownBy(()->service.save(new ProductionArtifactService.Request(task,null,null,null,"AcceptanceContract",acceptance,List.of(new ProductionArtifactService.Ref(s.path("artifactId").asText(),1,s.path("contentHash").asText()))))).hasMessageContaining("meaning");
        content.withArray("requirements").add(content.path("requirements").get(0).deepCopy());assertThatThrownBy(()->service.save(request("GameSpec",content))).hasMessageContaining("Duplicate requirement");
    }
    @Test void missingFieldsAndUnpinnedContractsRejected(){assertThatThrownBy(()->service.save(request("GameSpec",brief()))).isInstanceOf(IllegalArgumentException.class);var a=mapper.createObjectNode().put("title","Acceptance").put("specHash","missing");a.putArray("checks").addObject().put("id","a").put("expected","works").put("method","manual");assertThatThrownBy(()->service.save(request("AcceptanceContract",a))).hasMessageContaining("pinned");}
    @Test void existingDatabaseUpgradePreservesDocuments()throws Exception{
        var data=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+Files.createTempFile("agl-old-",".db"));
        String schema=new String(new org.springframework.core.io.ClassPathResource("schema.sql").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        String old=schema.replaceAll("(?s)CREATE TABLE IF NOT EXISTS production_artifact \\(.*?\\);","");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(old.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(data);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(data);jdbc.update("INSERT INTO task(title,goal) VALUES ('legacy','preserve')");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql")).execute(data);
        assertThat(jdbc.queryForObject("SELECT goal FROM task WHERE title='legacy'",String.class)).isEqualTo("preserve");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_artifact",Integer.class)).isZero();
    }
}
