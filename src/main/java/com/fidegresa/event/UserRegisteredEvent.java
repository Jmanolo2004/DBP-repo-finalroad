package com.fidegresa.event;

public class UserRegisteredEvent {
    private final String email;
    private final String name;

    public UserRegisteredEvent(String email, String name) {
    this.email = email;
    this.name = name;
    }
    public String getEmail(){
        return email;
    }
    public  String getName(){
        return name;
    }
}