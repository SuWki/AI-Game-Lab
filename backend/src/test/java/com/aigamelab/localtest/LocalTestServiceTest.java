package com.aigamelab.localtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalTestServiceTest {
    @TempDir Path dir;
    ObjectMapper mapper=new ObjectMapper(); JdbcTemplate db; LocalTestService service; Path catalog;
    @BeforeEach void setup()throws Exception {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("test.db")));
        db.execute("CREATE TABLE local_test_run(id TEXT PRIMARY KEY,record_json TEXT NOT NULL)");
        catalog=dir.resolve("targets.json");
        service=new LocalTestService(mapper,db,catalog.toString());
    }
    @AfterEach void cleanup(){service.close();}
    void target(String mode,String result,int timeout)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var t=new LocalTestService.Target("fixture","Any local game",dir.toString(),
                List.of(java,"-cp",System.getProperty("java.class.path"),Fixture.class.getName(),mode,dir.resolve("result.json").toString()),timeout,result);
        mapper.writeValue(catalog.toFile(),List.of(t));
    }
    LocalTestService.TestRun await(String id)throws Exception {
        long deadline=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<deadline) {
            var r=service.get(id);if(!Set.of("QUEUED","RUNNING").contains(r.status()))return r;
            Thread.sleep(20);
        }
        fail("Task did not terminate");return null;
    }
    @Test void passesAndPersistsEvidence()throws Exception {
        target("pass","result.json",10);var run=await(service.start("fixture").id());
        assertEquals("PASSED",run.status());assertEquals("PASS",run.evidence().path("status").asText());
        service.cancel(run.id());assertEquals("PASSED",service.get(run.id()).status());
        service.close();service=new LocalTestService(mapper,db,catalog.toString());
        assertEquals("PASSED",service.get(run.id()).status());
    }
    @Test void staleEvidenceCannotPass()throws Exception {
        Files.writeString(dir.resolve("result.json"),"{\"status\":\"PASS\"}");
        target("noop","result.json",10);
        assertEquals("INCONCLUSIVE",await(service.start("fixture").id()).status());
    }
    @Test void nonzeroCannotPassEvenWithPassEvidence()throws Exception {
        target("fail","result.json",10);assertEquals("FAILED",await(service.start("fixture").id()).status());
    }
    @Test void missingAndMalformedResultsDoNotPass()throws Exception {
        target("noop","result.json",10);assertEquals("INCONCLUSIVE",await(service.start("fixture").id()).status());
        target("malformed","result.json",10);assertEquals("FAILED",await(service.start("fixture").id()).status());
    }
    @Test void commandOnlyCompletionIsNotTestPass()throws Exception {
        target("noop",null,10);assertEquals("COMPLETED",await(service.start("fixture").id()).status());
    }
    @Test void timeoutAndCancellationAreTerminal()throws Exception {
        target("sleep",null,1);assertEquals("TIMEOUT",await(service.start("fixture").id()).status());
        target("sleep",null,10);var run=service.start("fixture");service.cancel(run.id());
        Thread.sleep(100);assertEquals("CANCELLED",service.get(run.id()).status());
    }
    @Test void pathEscapeRejectedBeforeExecution()throws Exception {
        target("pass","../outside.json",10);
        assertThrows(IllegalArgumentException.class,()->service.start("fixture"));
        assertTrue(service.list().isEmpty());
    }
    @Test void restartInterruptsPendingWork()throws Exception {
        var t=new LocalTestService.Target("old","old",dir.toString(),List.of("unused"),1,null);
        var run=new LocalTestService.TestRun("old",t,"RUNNING","then",null,null,"",null,null);
        db.update("INSERT INTO local_test_run VALUES (?,?)","old",mapper.writeValueAsString(run));
        service.close();service=new LocalTestService(mapper,db,catalog.toString());
        assertEquals("INTERRUPTED",service.get("old").status());
    }
    @Test void outputIsBounded()throws Exception {
        target("verbose",null,10);var run=await(service.start("fixture").id());
        assertEquals("COMPLETED",run.status());assertTrue(run.output().length()<=65536);assertTrue(run.output().endsWith("END"));
    }
    @Test void concurrentRunsCannotShareResultDirectory()throws Exception {
        target("sleep",null,10);var run=service.start("fixture");
        assertThrows(com.aigamelab.common.BusinessException.class,()->service.start("fixture"));
        service.cancel(run.id());assertEquals("CANCELLED",service.get(run.id()).status());
    }
    @Test void unknownResultStatusRemainsInconclusive()throws Exception {
        target("unknown","result.json",10);
        assertEquals("INCONCLUSIVE",await(service.start("fixture").id()).status());
    }
    @Test void freshReportForAnotherRunCannotPass()throws Exception {
        target("wrong-run","result.json",10);
        var run=await(service.start("fixture").id());
        assertEquals("INCONCLUSIVE",run.status());assertTrue(run.error().contains("runId"));
    }
    @Test void oversizedEvidenceIsRejected()throws Exception {
        target("oversize","result.json",10);
        var run=await(service.start("fixture").id());
        assertEquals("FAILED",run.status());assertTrue(run.error().contains("1 MiB"));
    }
    @Test void queueHasBoundedAdmissionAndRetainsRejection()throws Exception {
        target("sleep",null,10);
        var original=mapper.readValue(catalog.toFile(),LocalTestService.Target[].class)[0];
        List<LocalTestService.Target> targets=new ArrayList<>();
        for(int i=0;i<11;i++) {
            Path work=Files.createDirectory(dir.resolve("work"+i));
            targets.add(new LocalTestService.Target("game"+i,"game"+i,work.toString(),original.command(),10,null));
        }
        mapper.writeValue(catalog.toFile(),targets);
        for(int i=0;i<10;i++)service.start("game"+i);
        assertThrows(com.aigamelab.common.BusinessException.class,()->service.start("game10"));
        assertEquals(1,service.list().stream().filter(r->r.status().equals("REJECTED")).count());
    }
    public static class Fixture {
        public static void main(String[] args)throws Exception {
            if(args[0].equals("sleep")){Thread.sleep(30000);return;}
            if(args[0].equals("verbose")){System.out.print("x".repeat(200000)+"END");return;}
            if(args[0].equals("noop"))return;
            if(args[0].equals("oversize")){Files.writeString(Path.of(args[1]),"x".repeat(1048577));return;}
            String runId=args[0].equals("wrong-run")?"not-this-run":System.getenv("AGL_TEST_RUN_ID");
            Files.writeString(Path.of(args[1]),args[0].equals("malformed")?"not json":"{\"runId\":\""+runId+"\",\"status\":\""+(args[0].equals("unknown")?"DONE":"PASS")+"\",\"checks\":[\"generic-fixture\"]}");
            if(args[0].equals("fail"))System.exit(3);
        }
    }
}
