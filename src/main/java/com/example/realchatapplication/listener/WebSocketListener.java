package com.example.realchatapplication.listener;

import com.example.realchatapplication.model.ChatMessage;
import com.example.realchatapplication.repository.ChatMessageRepository;
import com.example.realchatapplication.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

@Component
public class WebSocketListener {

    @Autowired
    private UserService userService;

    @Autowired
    private SimpMessageSendingOperations messagingTemplate;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private static final Logger logger = LoggerFactory.getLogger(WebSocketListener.class);

    // Track custom session ID -> WebSocket session ID mapping
    private final ConcurrentHashMap<String, String> customSessionToWebSocket = new ConcurrentHashMap<>();
    // Track WebSocket session ID -> custom session ID mapping
    private final ConcurrentHashMap<String, String> webSocketToCustomSession = new ConcurrentHashMap<>();
    // Track active custom sessions per user
    private final ConcurrentHashMap<String, Set<String>> userToCustomSessions = new ConcurrentHashMap<>();
    // Track custom session ID -> username mapping
    private final ConcurrentHashMap<String, String> customSessionToUser = new ConcurrentHashMap<>();

    @EventListener
    public void handleWebsocketConnectListener(SessionConnectedEvent event){
        String webSocketSessionId = (String) event.getMessage().getHeaders().get("simpSessionId");
        logger.info("WebSocket connection established - WebSocket Session ID: {}", webSocketSessionId);
    }

    @EventListener
    public void handleWebsocketDisconnectListener(SessionDisconnectEvent event){
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String webSocketSessionId = headerAccessor.getSessionId();

        // Get custom session ID from WebSocket session
        String customSessionId = webSocketToCustomSession.remove(webSocketSessionId);

        if (customSessionId != null) {
            // Clean up mappings
            customSessionToWebSocket.remove(customSessionId);
            String username = customSessionToUser.get(customSessionId);

            if (username != null) {
                logger.info("WebSocket disconnection for custom session: {} (user: {}, WebSocket session: {})",
                        customSessionId, username, webSocketSessionId);

                // Remove custom session from user's active sessions
                Set<String> userCustomSessions = userToCustomSessions.get(username);
                if (userCustomSessions != null) {
                    userCustomSessions.remove(customSessionId);

                    // If no more sessions, set user offline and broadcast LEAVE
                    if (userCustomSessions.isEmpty()) {
                        userToCustomSessions.remove(username);
                        customSessionToUser.remove(customSessionId);
                        userService.setUserOnlineStatus(username, false);

                        logger.info("User {} went offline (no more active sessions)", username);

                        // Create and broadcast LEAVE message
                        try {
                            ChatMessage chatMessage = new ChatMessage();
                            chatMessage.setType(ChatMessage.MessageType.LEAVE);
                            chatMessage.setSender(username);
                            chatMessage.setTimestamp(LocalDateTime.now());
                            chatMessage.setContent("");

                            ChatMessage savedMessage = chatMessageRepository.save(chatMessage);
                            messagingTemplate.convertAndSend("/topic/public", savedMessage);

                            logger.info("Broadcast LEAVE message for user: {}", username);
                        } catch (Exception e) {
                            logger.error("Error broadcasting LEAVE message for user {}: {}", username, e.getMessage());
                        }
                    } else {
                        logger.info("User {} still has {} active sessions", username, userCustomSessions.size());
                    }
                }
            }
        } else {
            logger.warn("No custom session mapping found for disconnecting WebSocket session: {}", webSocketSessionId);
        }
    }

    // Method to register a custom session for a user
    public boolean registerCustomUserSession(String username, String customSessionId, String webSocketSessionId) {
        logger.info("Registering custom session {} for user {} with WebSocket session {}",
                customSessionId, username, webSocketSessionId);

        // Check if this custom session already exists for this user
        String existingUser = customSessionToUser.get(customSessionId);
        if (existingUser != null && existingUser.equals(username)) {
            // This is a reconnection with same custom session - just update the WebSocket mapping
            String oldWebSocketSession = customSessionToWebSocket.get(customSessionId);
            if (oldWebSocketSession != null) {
                webSocketToCustomSession.remove(oldWebSocketSession);
                logger.info("Updated WebSocket mapping for existing custom session {}: {} -> {}",
                        customSessionId, oldWebSocketSession, webSocketSessionId);
            }

            customSessionToWebSocket.put(customSessionId, webSocketSessionId);
            webSocketToCustomSession.put(webSocketSessionId, customSessionId);

            // This is a reconnection, not a new session
            return false;
        }

        // Add session mappings
        customSessionToWebSocket.put(customSessionId, webSocketSessionId);
        webSocketToCustomSession.put(webSocketSessionId, customSessionId);
        customSessionToUser.put(customSessionId, username);

        // Add custom session to user's session set
        Set<String> userCustomSessions = userToCustomSessions.computeIfAbsent(username,
                k -> ConcurrentHashMap.newKeySet());

        boolean isNewSession = userCustomSessions.add(customSessionId);
        boolean isFirstSession = userCustomSessions.size() == 1;

        logger.info("Registered custom session {} for user {}. Total sessions: {}. Is first session: {}",
                customSessionId, username, userCustomSessions.size(), isFirstSession);

        return isFirstSession;
    }

    // Method to get current session count for a user
    public int getUserSessionCount(String username) {
        Set<String> userCustomSessions = userToCustomSessions.get(username);
        return userCustomSessions != null ? userCustomSessions.size() : 0;
    }

    // Method to check if user has any active sessions
    public boolean hasActiveSessions(String username) {
        Set<String> userCustomSessions = userToCustomSessions.get(username);
        return userCustomSessions != null && !userCustomSessions.isEmpty();
    }

    // Method to force cleanup a user's sessions
    public void forceCleanupUser(String username) {
        Set<String> userCustomSessions = userToCustomSessions.remove(username);
        if (userCustomSessions != null) {
            for (String customSessionId : userCustomSessions) {
                String webSocketSessionId = customSessionToWebSocket.remove(customSessionId);
                if (webSocketSessionId != null) {
                    webSocketToCustomSession.remove(webSocketSessionId);
                }
                customSessionToUser.remove(customSessionId);
            }
        }
        userService.setUserOnlineStatus(username, false);
        logger.info("Force cleaned up {} sessions for user: {}",
                userCustomSessions != null ? userCustomSessions.size() : 0, username);
    }

    // Debug method to get all active sessions
    public void logAllActiveSessions() {
        logger.info("=== Active Sessions Debug ===");
        for (String username : userToCustomSessions.keySet()) {
            Set<String> sessions = userToCustomSessions.get(username);
            logger.info("User {}: {} sessions - {}", username, sessions.size(), sessions);
        }
        logger.info("Total custom session mappings: {}", customSessionToUser.size());
        logger.info("Total WebSocket mappings: {}", webSocketToCustomSession.size());
    }
}