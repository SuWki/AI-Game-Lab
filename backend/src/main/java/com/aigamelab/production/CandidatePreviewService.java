package com.aigamelab.production;

import com.aigamelab.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Separate loopback origins, fixed in-memory build bytes, no shell or arbitrary filesystem routes. */
@Service
public class CandidatePreviewService implements AutoCloseable {
    private record Preview(HttpServer server,ExecutorService executor,ObjectNode info){}
    private final Map<String,Preview> active=new HashMap<>();
    private final Map<String,ScheduledFuture<?>> timers=new HashMap<>();
    private final ScheduledThreadPoolExecutor expiry=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"candidate-preview-expiry");t.setDaemon(true);return t;});
    private final ProductionCandidateService candidates;private final ObjectMapper mapper;private final int ttl;
    public CandidatePreviewService(ProductionCandidateService candidates,ObjectMapper mapper,@Value("${production.preview-ttl-seconds:300}")int ttl){this.candidates=candidates;this.mapper=mapper;if(ttl<1||ttl>300)throw new IllegalArgumentException("Preview TTL must be 1..300 seconds");this.ttl=ttl;expiry.setRemoveOnCancelPolicy(true);}
    public synchronized ObjectNode start(String id){
        if(expiry.isShutdown())throw BusinessException.conflict("Preview service is stopping");
        var files=candidates.verifiedFiles(id);if(active.containsKey(id))return active.get(id).info().deepCopy();
        if(active.size()>=2)throw BusinessException.conflict("At most two candidate previews may run");
        try{
            var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8);String origin="http://127.0.0.1:"+server.getAddress().getPort(),prefix="/"+UUID.randomUUID()+"/";
            var pool=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"candidate-preview");t.setDaemon(true);return t;},new ThreadPoolExecutor.CallerRunsPolicy());server.setExecutor(pool);
            server.createContext("/",exchange->{
                try{
                    String path=exchange.getRequestURI().getRawPath();String name=path.equals(prefix)?"index.html":path.startsWith(prefix)?path.substring(prefix.length()):"";byte[] bytes=ProductionCandidateService.BUILD.contains(name)?files.get("build/"+name):null;
                    var headers=exchange.getResponseHeaders();headers.set("X-Content-Type-Options","nosniff");headers.set("Cache-Control","no-store");headers.set("Access-Control-Allow-Origin","null");headers.set("Cross-Origin-Opener-Policy","same-origin");headers.set("Referrer-Policy","no-referrer");
                    headers.set("Content-Security-Policy","sandbox allow-scripts; default-src 'none'; script-src "+origin+"; style-src "+origin+"; connect-src "+origin+"; img-src "+origin+" data:; frame-ancestors 'none'; form-action 'none'; base-uri 'none'");
                    if(!Set.of("GET","HEAD").contains(exchange.getRequestMethod())){exchange.sendResponseHeaders(405,-1);return;}
                    if(bytes==null){exchange.sendResponseHeaders(404,-1);return;}
                    headers.set("Content-Type",name.endsWith(".html")?"text/html; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":name.endsWith(".json")?"application/json":"text/javascript; charset=utf-8");
                    if("HEAD".equals(exchange.getRequestMethod())){headers.set("Content-Length",String.valueOf(bytes.length));exchange.sendResponseHeaders(200,-1);}else{exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}
                }finally{exchange.close();}
            });
            var info=mapper.createObjectNode().put("candidateId",id).put("url",origin+prefix).put("expiresAt",Instant.now().plusSeconds(ttl).toString()).put("status","RUNNING");var preview=new Preview(server,pool,info);active.put(id,preview);server.start();timers.put(id,expiry.schedule(()->expire(id,preview),ttl,TimeUnit.SECONDS));return info.deepCopy();
        }catch(java.io.IOException e){throw BusinessException.conflict("Cannot start candidate preview: "+e.getMessage());}
    }
    private synchronized void expire(String id,Preview expected){if(active.get(id)==expected)stop(id);}
    public synchronized ObjectNode stop(String id){var timer=timers.remove(id);if(timer!=null)timer.cancel(false);var previous=active.remove(id);if(previous!=null){previous.server().stop(0);previous.executor().shutdownNow();}return mapper.createObjectNode().put("candidateId",id).put("status","STOPPED");}
    @PreDestroy public synchronized void close(){for(String id:List.copyOf(active.keySet()))stop(id);expiry.shutdownNow();}
}
