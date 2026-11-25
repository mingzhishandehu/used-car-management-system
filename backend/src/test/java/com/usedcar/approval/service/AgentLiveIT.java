package com.usedcar.approval.service;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
 "logging.level.com.usedcar=INFO", "server.port=0"})
class AgentLiveIT {
 @Autowired JdbcTemplate db;
 @Autowired PasswordEncoder encoder;
 @Autowired ObjectMapper json;
 @LocalServerPort int port;
 String marker="live_"+UUID.randomUUID().toString().substring(0,8);
 String pass=UUID.randomUUID().toString();
 long admin, employee, first, second, conversation;
 String token;
 HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
 JsonNode post(String path,Object body,String auth) throws Exception {
  var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofMinutes(4))
   .header("Content-Type","application/json");
  if(auth!=null) builder.header("satoken",auth);
  return json.readTree(http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()).body());
 }
 long user(String suffix,String role) {
  db.update("insert into sys_user(username,password,real_name,role,status) values(?,?,?,?,1)",marker+suffix,encoder.encode(pass),marker,role);
  return db.queryForObject("select id from sys_user where username=?",Long.class,marker+suffix);
 }
 String login(String suffix) throws Exception {var r=post("/api/auth/login",Map.of("username",marker+suffix,"password",pass),null); assertEquals(0,r.path("code").asInt()); return r.path("data").path("tokenValue").asText();}
 long vehicle(String suffix) {
  db.update("insert into vehicle(stock_no,brand,model,purchase_price,purchase_date,list_price,status,created_by) values(?,?,?,50000,CURRENT_DATE,60000,'ON_SALE',?)",marker+suffix,"测试品牌",marker,admin);
  return db.queryForObject("select id from vehicle where stock_no=?",Long.class,marker+suffix);
 }
 int price(long id){return db.queryForObject("select list_price from vehicle where id=?",Integer.class,id);}
 long batch(List<Map<String,Object>> items) throws Exception {
  db.update("insert into vehicle_approval(operation_type,source,payload_json,applicant_id,status) values('AGENT_BATCH','AI',?,?,'PENDING')",json.writeValueAsString(Map.of("items",items)),admin);
  return db.queryForObject("select max(id) from vehicle_approval where applicant_id=?",Long.class,admin);
 }
 Map<String,Object> item(long id,int price){return Map.of("operation","CHANGE_PRICE","targetId",id,"payload",Map.of("newPrice",price,"reason","live test"));}
 @Test void realModelAndDatabase() throws Exception {
  try {
   admin=user("_a","ADMIN"); employee=user("_e","SALES"); first=vehicle("_1");second=vehicle("_2");token=login("_a");
   var created=post("/api/ai/conversations",Map.of(),token);assertEquals(0,created.path("code").asInt(),created.toString());conversation=created.path("data").path("id").asLong();assertTrue(conversation>0);
   var reply=post("/api/ai/conversations/"+conversation+"/messages",Map.of("content","请查询库存号 "+marker+"_1 和 "+marker+"_2 这两辆测试车，然后把两辆车挂牌价都改为58000元，生成一张批量操作申请等待管理员确认，不要操作其他车辆。"),token);
   assertEquals(0,reply.path("code").asInt(),reply.toString());
   System.out.println("LIVE_MODEL_REPLY="+reply.path("data").toString());
   var ids=db.queryForList("select id from vehicle_approval where applicant_id=? and operation_type='AGENT_BATCH'",Long.class,admin);
   assertEquals(1,ids.size(),"real model must propose exactly one batch");long approval=ids.get(0);
   var payload=json.readTree(db.queryForObject("select payload_json from vehicle_approval where id=?",String.class,approval));
   assertEquals(2,payload.path("items").size());Set<Long> targets=new HashSet<>();for(var i:payload.path("items")){targets.add(i.path("targetId").asLong());assertEquals("CHANGE_PRICE",i.path("operation").asText());assertEquals(58000,i.path("payload").path("newPrice").asInt());}assertEquals(Set.of(first,second),targets);
   assertEquals(60000,price(first));assertEquals(60000,price(second));
   assertEquals(403,post("/api/approvals/"+approval+"/approve",Map.of(),login("_e")).path("code").asInt());
   assertEquals(0,post("/api/approvals/"+approval+"/approve",Map.of(),token).path("code").asInt());
   assertEquals(58000,price(first));assertEquals(58000,price(second));
   assertNotEquals(0,post("/api/approvals/"+approval+"/approve",Map.of(),token).path("code").asInt());
   long bad=batch(List.of(item(first,57000),item(Long.MAX_VALUE,57000)));
   assertNotEquals(0,post("/api/approvals/"+bad+"/approve",Map.of(),token).path("code").asInt());assertEquals(58000,price(first));assertEquals("PENDING",db.queryForObject("select status from vehicle_approval where id=?",String.class,bad));
   var streamRequest=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/ai/conversations/"+conversation+"/messages/stream"))
    .timeout(Duration.ofMinutes(4)).header("satoken",token).header("Content-Type","application/json")
    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("content","请再为库存号 "+marker+"_1 和 "+marker+"_2 生成新的批量调价申请，都改为56000元，等待管理员确认。")))).build();
   String stream=http.send(streamRequest,HttpResponse.BodyHandlers.ofString()).body();
   System.out.println("LIVE_SSE="+stream);
   assertEquals(3,db.queryForObject("select count(*) from vehicle_approval where applicant_id=?",Integer.class,admin),"SSE must create the third proposal");
   assertEquals(58000,price(first));
   long stale=db.queryForObject("select max(id) from vehicle_approval where applicant_id=? and operation_type='AGENT_BATCH'",Long.class,admin);
   db.update("update vehicle set list_price=59000 where id=?",second);
   var staleResult=post("/api/approvals/"+stale+"/approve",Map.of(),token);
   assertEquals(409,staleResult.path("code").asInt(),staleResult.toString());
   assertEquals(58000,price(first));assertEquals(59000,price(second));
   assertEquals("PENDING",db.queryForObject("select status from vehicle_approval where id=?",String.class,stale));
   db.update("update vehicle set list_price=58000 where id=?",second);
   db.update("insert into vehicle_approval(operation_type,source,target_vehicle_id,payload_json,applicant_id,status) values('CHANGE_PRICE','MANUAL',?,?,?,'PENDING')",first,"{\"newPrice\":55000,\"reason\":\"live test\"}",admin);
   long child=db.queryForObject("select max(id) from vehicle_approval where applicant_id=?",Long.class,admin);
   long approvalBatch=batch(List.of(Map.of("operation","APPROVE","targetId",child,"payload",Map.of("remark","live test"))));
   assertEquals(0,post("/api/approvals/"+approvalBatch+"/approve",Map.of(),token).path("code").asInt());assertEquals(55000,price(first));
   long forbidden=batch(List.of(Map.of("operation","APPROVE","targetId",bad,"payload",Map.of())));
   assertNotEquals(0,post("/api/approvals/"+forbidden+"/approve",Map.of(),token).path("code").asInt());
   long rejection=batch(List.of(Map.of("operation","REJECT","targetId",child,"payload",Map.of("remark","test"))));
   assertNotEquals(0,post("/api/approvals/"+rejection+"/approve",Map.of(),token).path("code").asInt());
   System.out.println("LIVE_PASS: real model proposal, unchanged before confirmation, employee denial, admin execution, duplicate denial, atomic rollback");
  } finally {
   if(conversation>0){db.update("delete from ai_agent_trace where conversation_id=?",conversation);db.update("delete from ai_message where conversation_id=?",conversation);db.update("delete from ai_conversation where id=?",conversation);}
   if(admin>0) db.update("delete from vehicle_approval where applicant_id=?",admin);
   for(long id:new long[]{first,second}) if(id>0){db.update("delete from price_change_log where vehicle_id=?",id);db.update("delete from vehicle where id=?",id);}
   if(admin>0)db.update("delete from sys_user where id=?",admin);if(employee>0)db.update("delete from sys_user where id=?",employee);
  }
 }
}
