package com.example.realchatapplication.controller;

import com.example.realchatapplication.listener.WebSocketListener;
import com.example.realchatapplication.model.ChatMessage;
import com.example.realchatapplication.repository.ChatMessageRepository;
import com.example.realchatapplication.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.Map;

@Controller
public class ChatController {

    @Autowired
    private UserService userService;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private WebSocketListener webSocketListener;

    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    public ChatMessage addUser(@Payload ChatMessage chatMessage, SimpMessageHeaderAccessor headerAccessor) {

        if (chatMessage.getSender() == null || chatMessage.getSender().trim().isEmpty()) {
            System.out.println("Invalid sender in addUser request");
            return null;
        }

        String username = chatMessage.getSender().trim();
        String webSocketSessionId = headerAccessor.getSessionId();

        // Get custom session ID from message content or generate one
        String customSessionId = null;
        if (chatMessage.getContent() != null && chatMessage.getContent().startsWith("CUSTOM_SESSION:")) {
            customSessionId = chatMessage.getContent().substring("CUSTOM_SESSION:".length());
        }

        if (customSessionId == null || customSessionId.trim().isEmpty()) {
            System.out.println("No custom session ID provided in addUser request");
            return null;
        }

        if (userService.userExists(username)) {
            // Store username in session
            headerAccessor.getSessionAttributes().put("username", username);
            headerAccessor.getSessionAttributes().put("customSessionId", customSessionId);

            // Register this custom session - returns true if this is the user's first session
            boolean isFirstSession = webSocketListener.registerCustomUserSession(username, customSessionId, webSocketSessionId);

            // Set user online (safe to call multiple times)
            userService.setUserOnlineStatus(username, true);

            System.out.println("User '" + username + "' connected with custom session ID: " + customSessionId +
                    ", WebSocket session: " + webSocketSessionId +
                    ", first session: " + isFirstSession +
                    ", total sessions: " + webSocketListener.getUserSessionCount(username));

            // Only broadcast JOIN message if this is the user's first session
            if (isFirstSession) {
                chatMessage.setTimestamp(LocalDateTime.now());
                chatMessage.setContent(""); // Clear the custom session ID from content

                try {
                    ChatMessage savedMessage = chatMessageRepository.save(chatMessage);
                    System.out.println("Broadcasting JOIN message for user: " + username);
                    return savedMessage;
                } catch (Exception e) {
                    System.out.println("Error saving JOIN message for user " + username + ": " + e.getMessage());
                    return null;
                }
            } else {
                System.out.println("User " + username + " reconnected with existing session, not broadcasting JOIN message");
                return null;
            }
        } else {
            System.out.println("User " + username + " does not exist in database");
            return null;
        }
    }

    @MessageMapping("/chat.sendMessage")
    @SendTo("/topic/public")
    public ChatMessage sendMessage(@Payload ChatMessage chatMessage) {

        if (chatMessage.getSender() == null || chatMessage.getSender().trim().isEmpty()) {
            System.out.println("Invalid sender in sendMessage request");
            return null;
        }

        String username = chatMessage.getSender().trim();

        if (userService.userExists(username)) {
            if (chatMessage.getTimestamp() == null) {
                chatMessage.setTimestamp(LocalDateTime.now());
            }

            if (chatMessage.getContent() == null) {
                chatMessage.setContent("");
            }

            try {
                return chatMessageRepository.save(chatMessage);
            } catch (Exception e) {
                System.out.println("Error saving message from user " + username + ": " + e.getMessage());
                return null;
            }
        } else {
            System.out.println("Cannot send message: User " + username + " does not exist");
            return null;
        }
    }

    @MessageMapping("/chat.sendPrivateMessage")
    public void sendPrivateMessage(@Payload ChatMessage chatMessage, SimpMessageHeaderAccessor headerAccessor) {

        if (chatMessage.getSender() == null || chatMessage.getRecipient() == null ||
                chatMessage.getSender().trim().isEmpty() || chatMessage.getRecipient().trim().isEmpty()) {
            System.out.println("Invalid sender or recipient in private message request");
            return;
        }

        String sender = chatMessage.getSender().trim();
        String recipient = chatMessage.getRecipient().trim();

        if (userService.userExists(sender) && userService.userExists(recipient)) {

            if (chatMessage.getTimestamp() == null) {
                chatMessage.setTimestamp(LocalDateTime.now());
            }

            if (chatMessage.getContent() == null) {
                chatMessage.setContent("");
            }

            chatMessage.setType(ChatMessage.MessageType.PRIVATE_MESSAGE);

            try {
                ChatMessage savedMessage = chatMessageRepository.save(chatMessage);
                System.out.println("Private message saved successfully with Id " + savedMessage.getId());

                String recipientDestination = "/user/" + recipient + "/queue/private";
                System.out.println("Sending message to recipient destination: " + recipientDestination);
                messagingTemplate.convertAndSend(recipientDestination, savedMessage);

                String senderDestination = "/user/" + sender + "/queue/private";
                System.out.println("Sending message to sender destination: " + senderDestination);
                messagingTemplate.convertAndSend(senderDestination, savedMessage);

            } catch (Exception e) {
                System.out.println("ERROR occurred while sending private message: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("ERROR: Sender '" + sender + "' or recipient '" + recipient + "' does not exist");
        }
    }
}