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

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断是关注还是取关
        if (isFollow) {
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            //保存成功后，在redis中添加一条记录，key：follow:userId value：followUserId
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add("follow:" + userId, followUserId.toString());
            }
        } else {
            //取关，删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId).eq("follow_user_id",followUserId));
            //取关成功后，在redis中删除一条记录，key：follow:userId value：followUserId
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove("follow:" + userId, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //求交集
        String key1 = "follow:" + userId;
        String key2 = "follow:" + followUserId;
        Set<String> commons = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (commons == null || commons.isEmpty()) {
            //没有共同关注
            return Result.ok(Collections.emptyList());
        }
        //解析id集合
        List<Long> ids = commons.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
