package org.odyssey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.odyssey.model.OnesTicket;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OnesClient 解析逻辑单测：样例 JSON 均取自 2026-11-02 实测抓包（工单 #378838、调研文档 §7）。
 */
class OnesClientParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 工单解析_客户字段_产品线_时间换算() throws Exception {
        String json = "{\"data\":{\"buckets\":[{\"tasks\":[{"
                + "\"_AST3JVZw\":{\"uuid\":\"Vfvy75iN\",\"value\":\"赛孚士\"},"
                + "\"assign\":{\"name\":\"游建成\",\"uuid\":\"Xr15YzY5\"},"
                + "\"createTime\":1793615397426704,"
                + "\"deadline\":null,"
                + "\"descriptionText\":\"描述正文\","
                + "\"name\":\"【赛孚士】D2推送到TMS的CR文件预览空白\","
                + "\"number\":378838,"
                + "\"owner\":{\"name\":\"蒲万银\",\"uuid\":\"RbNiGDvm\"},"
                + "\"priority\":{\"value\":\"低\"},"
                + "\"productModules\":[{\"name\":\"培训管理\",\"uuid\":\"SNYNXMJN\"}],"
                + "\"products\":[{\"name\":\"TMS\",\"uuid\":\"NfvccPP53MS6Ceik\"}],"
                + "\"serverUpdateStamp\":1793615404029526,"
                + "\"status\":{\"category\":\"to_do\",\"name\":\"待办\"},"
                + "\"uuid\":\"RbNiGDvmXB8RRW3h\"}]}]}}";

        List<OnesTicket> tickets = OnesClient.parseTickets(mapper.readTree(json));
        assertEquals(1, tickets.size());

        OnesTicket t = tickets.get(0);
        assertEquals("RbNiGDvmXB8RRW3h", t.getUuid());
        assertEquals(378838, t.getNumber());
        assertEquals("赛孚士", t.getCustomerName());
        assertEquals("Vfvy75iN", t.getCustomerUuid());
        assertEquals("游建成", t.getAssignName());
        assertEquals("Xr15YzY5", t.getAssignUuid());
        assertEquals("to_do", t.getStatusCategory());
        assertEquals("TMS", t.getProducts().get(0).getName());
        assertEquals("培训管理", t.getModules().get(0).getName());
        // 微秒 → 毫秒换算
        assertEquals(1793615397426L, t.getCreateTimeMs());
        assertEquals(1793615404029L, t.getServerUpdateStampMs());
        assertNull(t.getDeadline());
    }

    @Test
    void 动态流解析_评论与字段变更分离() throws Exception {
        String json = "["
                + "{\"uuid\":\"xeIZX4us\",\"type\":\"discussion\",\"from\":\"7N3aN44w\","
                + "\"send_time\":1793601294737510,\"text\":\"同步问题，已重新同步\"},"
                + "{\"type\":\"system\",\"subject_id\":\"AxQKZC1B\","
                + "\"send_time\":1793601879091274,\"action\":\"update\",\"object_attr\":\"field\","
                + "\"ext\":{\"field_name\":\"状态\",\"field_uuid\":\"field005\","
                + "\"old_option\":{\"name\":\"待办\",\"uuid\":\"SkzkxJro\"},"
                + "\"new_option\":{\"name\":\"已废弃\",\"uuid\":\"G1PGGxSG\"}}},"
                + "{\"type\":\"system\",\"subject_id\":\"AxQKZC1B\","
                + "\"send_time\":1793601879091275,\"action\":\"update\",\"object_attr\":\"field\","
                + "\"ext\":{\"field_name\":\"负责人\",\"old_value\":\"张三\",\"new_value\":\"李四\"}}"
                + "]";

        org.odyssey.model.OnesTaskMessages msgs =
                OnesClient.parseMessages("taskUuid1", mapper.readTree(json));

        assertEquals(1, msgs.getComments().size());
        assertEquals("同步问题，已重新同步", msgs.getComments().get(0).getText());
        assertEquals("7N3aN44w", msgs.getComments().get(0).getFromUuid());
        assertEquals(1793601294737L, msgs.getComments().get(0).getSendTimeMs());

        assertEquals(2, msgs.getHistories().size());
        // 选项型变更取 option.name
        assertEquals("状态", msgs.getHistories().get(0).getFieldName());
        assertEquals("待办", msgs.getHistories().get(0).getOldValue());
        assertEquals("已废弃", msgs.getHistories().get(0).getNewValue());
        // 非选项型变更回落 old_value/new_value
        assertEquals("张三", msgs.getHistories().get(1).getOldValue());
        assertEquals("李四", msgs.getHistories().get(1).getNewValue());
    }

    @Test
    void 嵌套包装的动态流也能解析() throws Exception {
        String json = "{\"messages\":[{\"uuid\":\"c1\",\"type\":\"discussion\","
                + "\"from\":\"u1\",\"send_time\":1793601294737510,\"text\":\"hi\"}]}";
        org.odyssey.model.OnesTaskMessages msgs =
                OnesClient.parseMessages("t", mapper.readTree(json));
        assertEquals(1, msgs.getComments().size());
    }

    @Test
    void 微秒转毫秒() {
        assertEquals(1793611085494L, OnesClient.microsToMillis(1793611085494130L));
        assertTrue(OnesClient.microsToMillis(0) == 0);
    }
}
