package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

/**
 * <p>
 *  鏈嶅姟绫?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 鍙戦€佹墜鏈洪獙璇佺爜
     * @param phone 鎵嬫満鍙?
     * @return 缁撴灉
     */
    Result sendCode(String phone);

    /**
     * 鐧诲綍鍔熻兘
     * @param loginForm 鐧诲綍鍙傛暟锛屽寘鍚墜鏈哄彿銆侀獙璇佺爜锛涙垨鑰呮墜鏈哄彿銆佸瘑鐮?
     * @return 缁撴灉
     */
    Result login(LoginFormDTO loginForm);

    /**
     * 鐧诲嚭鍔熻兘
     * @return 缁撴灉
     */
    Result logout();

    /**
     * 绛惧埌
     * @return 缁撴灉
     */
    Result sign();

    /**
     * 缁熻杩炵画绛惧埌鐨勫ぉ鏁?
     * @return 缁撴灉
     */
    Result signCount();
}

