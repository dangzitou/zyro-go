package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    /**
     * Agent tool calls may hop to child threads during model orchestration.
     * InheritableThreadLocal keeps the authenticated user context available in that path.
     */
    private static final InheritableThreadLocal<UserDTO> tl = new InheritableThreadLocal<>();

    public static void saveUser(UserDTO userDTO){
        tl.set(userDTO);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
