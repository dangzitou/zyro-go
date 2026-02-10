package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注或取关
     * @param followUserId 关注用户id
     * @param isFollow 是否关注
     * @return 结果
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 是否关注
     * @param followUserId 关注用户id
     * @return 结果
     */
    Result isFollow(Long followUserId);

    /**
     * 共同关注
     * @param followUserId 关注用户id
     * @return 结果
     */
    Result followCommons(Long followUserId);
}
