package com.usedcar.approval.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usedcar.system.domain.SysUser;
import com.usedcar.common.BizException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.Map;
import com.usedcar.vehicle.mapper.VehicleMapper;
import com.usedcar.vehicle.domain.Vehicle;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentBatchServiceTest {
 @Test void adminStillCreatesPendingApproval() {
  var gate = mock(ApprovalGate.class);
  var user = new SysUser(); user.setId(1L); user.setRole("ADMIN");
  when(gate.current()).thenReturn(user);
  when(gate.insert(eq("AGENT_BATCH"), eq("AI"), isNull(), any(), eq(1L), isNull())).thenReturn(42L);
  try (var factory = Validation.buildDefaultValidatorFactory()) {
   var vehicles = mock(VehicleMapper.class);
   when(vehicles.selectById(7L)).thenReturn(new Vehicle().setId(7L).setStatus("ON_SALE"));
   var service = new AgentBatchService(gate, new ObjectMapper(), factory.getValidator(), vehicles);
   var result = service.propose(Map.of("planJson", "{\"items\":[{\"operation\":\"CHANGE_PRICE\",\"targetId\":7,\"payload\":{\"newPrice\":100}}]}"));
   assertEquals("PENDING", result.get("status"));
   assertEquals(42L, result.get("approvalId"));
   assertEquals(2, service.parse("{\"items\":[{\"operation\":\"CHANGE_PRICE\",\"targetId\":7,\"payload\":{\"newPrice\":100}},{\"operation\":\"APPROVE\",\"targetId\":7,\"payload\":{}}]}").items().size());
   assertThrows(BizException.class, () -> service.parse("{\"items\":[{\"operation\":\"REJECT\",\"targetId\":7,\"payload\":{\"remark\":null}}]}"));
   assertThrows(BizException.class, () -> service.parse("{\"items\":[]}"));
   assertThrows(BizException.class, () -> service.parse("{\"items\":[{\"operation\":\"DELETE_ALL\",\"targetId\":7,\"payload\":{}}]}"));
  }
 }
}
