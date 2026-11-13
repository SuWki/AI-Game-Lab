package com.aigamelab.api;
import com.aigamelab.localtest.LocalTestService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class LocalTestControllerTest {
    LocalTestService service; MockMvc mvc;
    @TempDir Path dir;
    @BeforeEach void setup()throws Exception {
        var mapper=new ObjectMapper();
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("test.db")));
        db.execute("CREATE TABLE local_test_run(id TEXT PRIMARY KEY,record_json TEXT NOT NULL)");
        var catalog=dir.resolve("targets.json");
        mapper.writeValue(catalog.toFile(),List.of(new LocalTestService.Target("a","game",dir.toString(),List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-version"),5,null)));
        service=new LocalTestService(mapper,db,catalog.toString());
        mvc=MockMvcBuilders.standaloneSetup(new LocalTestController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void cleanup(){service.close();}
    @Test void requiresExplicitHeader()throws Exception {
        mvc.perform(post("/api/local-tests/targets/a/runs")).andExpect(status().isBadRequest());
        assertTrue(service.list().isEmpty());
    }
    @Test void rejectsRemoteAndUntrustedHost()throws Exception {
        mvc.perform(post("/api/local-tests/targets/a/runs").header("X-AGL-Local-Test","1").with(r->{r.setRemoteAddr("192.0.2.1");return r;})).andExpect(status().isBadRequest());
        mvc.perform(post("/api/local-tests/targets/a/runs").header("X-AGL-Local-Test","1").with(r->{r.setRemoteAddr("127.0.0.1");r.setServerName("evil.example");return r;})).andExpect(status().isBadRequest());
        assertTrue(service.list().isEmpty());
    }
    @Test void localRequestUsesRegisteredTargetOnly()throws Exception {
        mvc.perform(post("/api/local-tests/targets/a/runs").header("X-AGL-Local-Test","1").with(r->{r.setRemoteAddr("127.0.0.1");return r;})).andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(0));
        assertEquals(1,service.list().size());
    }
}
