package com.example.realchatapplication.controller;

import com.example.realchatapplication.listener.WebSocketListener;
import com.example.realchatapplication.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/debug")
public class DebugController {

    @Autowired
    private UserService userService;

    @Autowired
    private WebSocketListener webSocketListener;

    @GetMapping("/sessions/{username}")
    public ResponseEntity<Map<String, Object>> getUserSessions(@PathVariable String username) {
        Map<String, Object> response = new HashMap<>();
        response.put("username", username);
        response.put("sessionCount", webSocketListener.getUserSessionCount(username));
        response.put("hasActiveSessions", webSocketListener.hasActiveSessions(username));
        response.put("isOnlineInDB", userService.isUserOnline(username));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/cleanup/{username}")
    public ResponseEntity<String> forceCleanupUser(@PathVariable String username) {
        webSocketListener.forceCleanupUser(username);
        return ResponseEntity.ok("Force cleaned up user: " + username);
    }

    @PostMapping("/reset-all-users")
    public ResponseEntity<String> resetAllUsers() {
        userService.setAllUsersOffline();
        return ResponseEntity.ok("Set all users offline");
    }

    @GetMapping("/log-all-sessions")
    public ResponseEntity<String> logAllSessions() {
        webSocketListener.logAllActiveSessions();
        return ResponseEntity.ok("Logged all active sessions to console");
    }
}