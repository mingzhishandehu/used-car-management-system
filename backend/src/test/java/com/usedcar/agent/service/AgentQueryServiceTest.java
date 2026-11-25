package com.usedcar.agent.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.agent.client.SiliconFlowClient;
import com.usedcar.agent.config.AiProperties;
import com.usedcar.agent.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentQueryServiceTest {
 @Test void continuesAfterFixedTool() {
  var client = mock(SiliconFlowClient.class);
  var registry = mock(ToolRegistry.class);
  var sql = mock(ReadonlySqlTool.class);
  var props = new AiProperties("", "test", "test", "", 1000, 1000, 20, 10, false, 24, 16);
  when(registry.descriptions()).thenReturn(List.of());
  when(sql.description()).thenReturn("");
  when(client.chat(anyList())).thenReturn(
   "{\"intent\":\"QUERY\",\"tool\":\"count_vehicles\",\"args\":{}}",
   "{\"intent\":\"QUERY\",\"tool\":\"count_pending_approvals\",\"args\":{}}",
   "{\"intent\":\"FINISH\"}", "{\"reply\":\"4 cars, 2 approvals\"}");
  when(registry.execute(eq("count_vehicles"), anyMap())).thenReturn(Map.of("total", 4));
  when(registry.execute(eq("count_pending_approvals"), anyMap())).thenReturn(Map.of("pending", 2));
  var service = new AgentQueryService(client, props, registry, sql, new ObjectMapper(), new AnswerNumberGuard(), mock(AgentTraceRecorder.class));
  assertEquals("4 cars, 2 approvals", service.tryAnswer("Count cars and approvals", List.of()));
  verify(registry).execute(eq("count_pending_approvals"), anyMap());
  verify(client, times(4)).chat(anyList());
 }
}
