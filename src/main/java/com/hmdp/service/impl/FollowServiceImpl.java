package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * <p>
 *  鏈嶅姟瀹炵幇绫?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注或取关用户。
     * 同时会把关注关系同步到 Redis Set，方便后续做共同关注计算。
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //鑾峰彇鐧诲綍鐢ㄦ埛
        Long userId = UserHolder.getUser().getId();
        //鍒ゆ柇鏄叧娉ㄨ繕鏄彇鍏?
        if (isFollow) {
            //鍏虫敞锛屾柊澧炴暟鎹?
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            //淇濆瓨鎴愬姛鍚庯紝鍦╮edis涓坊鍔犱竴鏉¤褰曪紝key锛歠ollow:userId value锛歠ollowUserId
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add("follow:" + userId, followUserId.toString());
            }
        } else {
            //鍙栧叧锛屽垹闄ゆ暟鎹?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId).eq("follow_user_id",followUserId));
            //鍙栧叧鎴愬姛鍚庯紝鍦╮edis涓垹闄や竴鏉¤褰曪紝key锛歠ollow:userId value锛歠ollowUserId
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove("follow:" + userId, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 判断当前登录用户是否已关注目标用户。
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 查询当前用户与目标用户的共同关注列表。
     */
    @Override
    public Result followCommons(Long followUserId) {
        //鑾峰彇鐧诲綍鐢ㄦ埛
        Long userId = UserHolder.getUser().getId();
        //姹備氦闆?
        String key1 = "follow:" + userId;
        String key2 = "follow:" + followUserId;
        Set<String> commons = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (commons == null || commons.isEmpty()) {
            //娌℃湁鍏卞悓鍏虫敞
            return Result.ok(Collections.emptyList());
        }
        //瑙ｆ瀽id闆嗗悎
        List<Long> ids = commons.stream().map(Long::valueOf).collect(Collectors.toList());
        //鏌ヨ鐢ㄦ埛
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}

