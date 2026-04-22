package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 鍓嶇鎺у埗鍣?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 鍙戦€佹墜鏈洪獙璇佺爜
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 鐧诲綍鍔熻兘
     * @param loginForm 鐧诲綍鍙傛暟锛屽寘鍚墜鏈哄彿銆侀獙璇佺爜锛涙垨鑰呮墜鏈哄彿銆佸瘑鐮?
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm){
        return userService.login(loginForm);
    }

    /**
     * 鐧诲嚭鍔熻兘
     * @return 鏃?
     */
    @PostMapping("/logout")
    public Result logout(){
        //鐢ㄦ埛閫€鍑虹櫥褰曪紝鍒犻櫎session涓繚瀛樼殑鐢ㄦ埛淇℃伅
        return userService.logout();
    }

    @GetMapping("/me")
    public Result me(){
        UserDTO userDTO = UserHolder.getUser();
        return Result.ok(userDTO);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 鏌ヨ璇︽儏
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 娌℃湁璇︽儏锛屽簲璇ユ槸绗竴娆℃煡鐪嬭鎯?
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 杩斿洖
        return Result.ok(info);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 鏌ヨ璇︽儏
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 杩斿洖
        return Result.ok(userDTO);
    }

    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }
}

