package com.juzo.ai.ordermanager.advisor.web;

import com.juzo.ai.ordermanager.advisor.agent.AdvisorAgent;
import com.juzo.ai.ordermanager.advisor.agent.ConversationStore;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import com.juzo.ai.ordermanager.advisor.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ChatController.class, UiConfigController.class})
@Import({SecurityConfig.class, ChatControllerSecurityTest.StoreConfig.class})
@EnableConfigurationProperties(AdvisorProperties.class)
@TestPropertySource(properties = {
        "AAD_TENANT_ID=tenant", "AAD_CLIENT_ID=client"})
class ChatControllerSecurityTest {

    static class StoreConfig {
        @org.springframework.context.annotation.Bean
        ConversationStore conversationStore() {
            return new TestStore();
        }
    }

    static class TestStore extends ConversationStore {
        TestStore() {
            super(new AdvisorProperties(null, null, null, 0, 0, 0, false, null, Duration.ofHours(1),
                    "http://x/mcp", null, null, null, true));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ConversationStore store;

    @MockitoBean
    AdvisorAgent agent;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static RequestPostProcessor trader(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority(SecurityConfig.REQUIRED_SCOPE));
    }

    @Test
    void configIsPublic() throws Exception {
        mvc.perform(get("/config.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("client"))
                .andExpect(jsonPath("$.scope").value("api://client/mcp.trade"));
    }

    @Test
    void apiRequiresToken() throws Exception {
        mvc.perform(post("/api/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void apiRequiresMcpTradeScope() throws Exception {
        mvc.perform(post("/api/conversations").with(jwt().jwt(j -> j.subject("a"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsConversationForTrader() throws Exception {
        mvc.perform(post("/api/conversations").with(trader("a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversationId").exists());
    }

    @Test
    void otherUsersConversationIsNotFound() throws Exception {
        UUID id = store.create("owner").id();

        mvc.perform(post("/api/conversations/" + id + "/messages").with(trader("intruder"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidAccountIdIsRejected() throws Exception {
        UUID id = store.create("a").id();

        mvc.perform(post("/api/conversations/" + id + "/messages").with(trader("a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\",\"accountId\":\"12345\"}"))
                .andExpect(status().isBadRequest());
    }
}
