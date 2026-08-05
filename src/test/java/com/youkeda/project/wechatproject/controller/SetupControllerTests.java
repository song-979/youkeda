package com.youkeda.project.wechatproject.controller;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SetupControllerTests {

    private final SetupController controller = new SetupController(
            mock(ILinkClient.class), mock(MessageBridge.class));

    @Test
    void rejectsSensitiveApiAccessFromNonLoopbackAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        assertThat(controller.getConfig(request).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.loginStatus(request).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.qrcodeContent(request).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @SuppressWarnings("unchecked")
    void masksSensitiveKeysRecursivelyIncludingMapsInsideLists() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("api-key", "secret-a");
        nested.put("normal", "visible");
        nested.put("items", List.of(Map.of("password", "secret-b", "name", "visible-name")));
        Method method = SetupController.class.getDeclaredMethod("maskSensitive", Map.class, String.class);
        method.setAccessible(true);

        Map<String, Object> masked = (Map<String, Object>) method.invoke(controller, nested, "");

        assertThat(masked.get("api-key")).isEqualTo("********");
        assertThat(masked.get("normal")).isEqualTo("visible");
        Map<String, Object> listItem = (Map<String, Object>) ((List<?>) masked.get("items")).getFirst();
        assertThat(listItem).containsEntry("password", "********").containsEntry("name", "visible-name");
    }
}
