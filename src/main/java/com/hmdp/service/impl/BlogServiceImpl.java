package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //查询点赞状态
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryByBlogId(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("博文不存在");
        }
        //查询用户
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //查询点赞状态
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //用户未登录，直接返回
            return;
        }
        //获取登录用户
        Long userId = user.getId();
        //判断是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //判断用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null){
            //如果未点赞，点赞数加一，并保存用户到redis集合
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //如果已点赞，点赞数减一
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //查询top5点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据用户id查询用户并转换为DTO
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(isSuccess){
            //查询笔记作者的所有粉丝
            List<Follow> fans = followService.query().eq("follow_user_id", user.getId()).list();
            //推送笔记id给所有粉丝
            for (Follow fan : fans) {
                stringRedisTemplate.opsForZSet().add(FEED_KEY + fan.getUserId(), blog.getId().toString(), System.currentTimeMillis());
            }
            return Result.ok(blog.getId());
        }
        return Result.fail("笔记保存失败");
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        //查询收件箱 ZREVRANGEBYSCORE key Max Min WITHSCO
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            log.info("没有查询到关注者的笔记");
            return Result.ok(Collections.emptyList());
        }
        log.info("查询到关注者的笔记，数量：{}", typedTuples.size());
        //解析数据：blogId、score（时间戳）
        ArrayList<Object> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(typedTuple.getValue());
            long time = typedTuple.getScore().longValue();
            if (time == minTime){
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        String idStr = StrUtil.join(",", ids);
        log.info("ids:{}", ids);
        //根据id查询blog
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //查询blog有关的用户
            User user = userService.getById(blog.getUserId());
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //查询blog是否被点赞
            isBlogLiked(blog);
        }
        //封装并返回
        ScrollResult r = new ScrollResult();
        log.info("blogs:{}", blogs);
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }
}
