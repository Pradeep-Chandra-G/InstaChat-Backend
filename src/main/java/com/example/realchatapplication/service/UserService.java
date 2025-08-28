package com.example.realchatapplication.service;

import com.example.realchatapplication.model.User;
import com.example.realchatapplication.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @PostConstruct
    public void initializeUserStates() {
        // Clean up any stale online states when application starts
        userRepository.setAllUsersOffline();
        System.out.println("Initialized all users to offline state");
    }

    public boolean userExists(String username){
        return userRepository.existsByUsername(username);
    }

    public boolean isUserOnline(String username) {
        return userRepository.findByUsername(username)
                .map(User::isOnline)
                .orElse(false);
    }

    public void setUserOnlineStatus(String username, boolean isOnline){
        userRepository.updateUserOnlineStatus(username, isOnline);
        System.out.println("Set user " + username + " online status to: " + isOnline);
    }

    public void setAllUsersOffline() {
        userRepository.setAllUsersOffline();
    }
}