package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送手机验证码
     * @param phone 手机号
     * @param session 会话
     * @return 结果
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session 会话
     * @return 结果
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 登出功能
     * @return 结果
     */
    Result logout();

    /**
     * 签到
     * @return 结果
     */
    Result sign();

    /**
     * 统计连续签到的天数
     * @return 结果
     */
    Result signCount();
}
