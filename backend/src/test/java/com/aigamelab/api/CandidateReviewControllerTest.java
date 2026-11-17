package com.aigamelab.api;

import com.aigamelab.production.CandidateReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CandidateReviewControllerTest {
 CandidateReviewService reviews;MockMvc mvc;final ObjectMapper mapper=new ObjectMapper();
 @BeforeEach void setup(){reviews=mock(CandidateReviewService.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));mvc=MockMvcBuilders.standaloneSetup(new CandidateReviewController(reviews)).setControllerAdvice(new GlobalExceptionHandler()).build();}
 @Test void writesAndReceiptRequireLocalMarker()throws Exception{
  mvc.perform(post("/api/production-candidates/c/review").contentType("application/json").content("{}")).andExpect(status().isBadRequest());mvc.perform(get("/api/production-candidates/c/review/receipt")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/production-candidates/c/review").contentType("application/json").content("{}").header("X-AGL-Local-Test","1").with(r->{r.setRemoteAddr("203.0.113.5");return r;})).andExpect(status().isBadRequest());verifyNoInteractions(reviews);
 }
 @Test void readsAndHistoryExposeSeparateVersionedReview()throws Exception{
  when(reviews.view("c")).thenReturn(mapper.createObjectNode().put("revision",2));when(reviews.history("c",2)).thenReturn(java.util.List.of(mapper.createObjectNode().put("revision",1)));
  mvc.perform(get("/api/production-candidates/c/review")).andExpect(jsonPath("$.data.revision").value(2));mvc.perform(get("/api/production-candidates/c/review/history?beforeRevision=2")).andExpect(jsonPath("$.data[0].revision").value(1));
 }
 @Test void postRetainsExpectedRevisionAndManifestPreconditions()throws Exception{
  when(reviews.save(eq("c"),any())).thenReturn(mapper.createObjectNode().put("revision",1));mvc.perform(post("/api/production-candidates/c/review").header("X-AGL-Local-Test","1").contentType("application/json").content("{\"expectedRevision\":0,\"manifestHash\":\"fixed\",\"reviewer\":\"Operator\",\"summary\":\"Observed\",\"decision\":\"DRAFT\",\"checks\":[]}")).andExpect(jsonPath("$.data.revision").value(1));verify(reviews).save(eq("c"),argThat(r->r.expectedRevision()==0&&"fixed".equals(r.manifestHash())&&"DRAFT".equals(r.decision())));
 }
 @Test void receiptDownloadsJsonWithoutCaching()throws Exception{
  byte[] bytes="{\"revision\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);when(reviews.receipt("c")).thenReturn(bytes);mvc.perform(get("/api/production-candidates/c/review/receipt").header("X-AGL-Local-Test","1")).andExpect(content().bytes(bytes)).andExpect(content().contentType("application/json")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Content-Disposition","attachment; filename=agl-review-c.json"));
 }
}
