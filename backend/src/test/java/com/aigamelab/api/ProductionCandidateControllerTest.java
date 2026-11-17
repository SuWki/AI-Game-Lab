package com.aigamelab.api;

import com.aigamelab.production.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductionCandidateControllerTest {
 ProductionCandidateService candidates;CandidatePreviewService previews;MockMvc mvc;final ObjectMapper mapper=new ObjectMapper();
 @BeforeEach void setup(){candidates=mock(ProductionCandidateService.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));previews=mock(CandidatePreviewService.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));mvc=MockMvcBuilders.standaloneSetup(new ProductionCandidateController(candidates,previews)).setControllerAdvice(new GlobalExceptionHandler()).build();}
 @Test void mutationsAndDownloadRequireLocalMarker()throws Exception{
  mvc.perform(post("/api/productions/p/candidates").contentType("application/json").content("{\"validationRunId\":\"r\"}")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/production-candidates/c/download")).andExpect(status().isBadRequest());mvc.perform(post("/api/production-candidates/c/preview")).andExpect(status().isBadRequest());verifyNoInteractions(candidates,previews);
 }
 @Test void returnsBinaryArchiveWithAttachmentAndNoCaching()throws Exception{
  byte[] bytes={80,75,3,4};when(candidates.get("c")).thenReturn(mapper.createObjectNode().put("id","c"));when(candidates.download("c")).thenReturn(bytes);
  mvc.perform(get("/api/production-candidates/c/download").header("X-AGL-Local-Test","1")).andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(content().contentType("application/zip")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Content-Disposition","attachment; filename=agl-candidate-c.zip"));
 }
 @Test void previewAndStopUseTheCandidateResource()throws Exception{
  when(previews.start("c")).thenReturn(mapper.createObjectNode().put("candidateId","c").put("url","http://127.0.0.1:4180/"));when(previews.stop("c")).thenReturn(mapper.createObjectNode().put("status","STOPPED"));
  mvc.perform(post("/api/production-candidates/c/preview").header("X-AGL-Local-Test","1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateId").value("c"));
  mvc.perform(post("/api/production-candidates/c/preview/stop").header("X-AGL-Local-Test","1")).andExpect(jsonPath("$.data.status").value("STOPPED"));
 }
 @Test void remoteClientsCannotStartPreviewEvenWithMarker()throws Exception{
  mvc.perform(post("/api/production-candidates/c/preview").header("X-AGL-Local-Test","1").with(request->{request.setRemoteAddr("203.0.113.5");return request;})).andExpect(status().isBadRequest());verifyNoInteractions(previews);
 }
}
