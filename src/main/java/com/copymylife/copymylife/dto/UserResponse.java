package com.copymylife.copymylife.dto;

public class UserResponse {
    private Long id;
    private String name;
    private String email;

    public void setId(Long id) {
        this.id = id;
    }
    public long getId(){
        return id;
    }

    public void setEmail(String email) {
        this.email = email;
    }
    public String getEmail(){
        return email;
    }

    public void setName(String name) {
        this.name = name;
    }
    public String getName(){
        return name;
    }

    
}
