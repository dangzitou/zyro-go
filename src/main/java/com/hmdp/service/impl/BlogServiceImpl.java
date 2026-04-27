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

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  鏈嶅姟瀹炵幇绫?
 * </p>
 *
 * @author 铏庡摜
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

    /**
     * 查询热门博客列表，并补齐作者信息和当前用户点赞态。
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 鏍规嵁鐢ㄦ埛鏌ヨ
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 鑾峰彇褰撳墠椤垫暟鎹?
        List<Blog> records = page.getRecords();
        // 鏌ヨ鐢ㄦ埛
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //鏌ヨ鐐硅禐鐘舵€?
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据博客 id 查询博客详情。
     */
    @Override
    public Result queryByBlogId(Long id) {
        //鏌ヨblog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("博客不存在");
        }
        //鏌ヨ鐢ㄦ埛
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //鏌ヨ鐐硅禐鐘舵€?
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 给博客补齐“当前登录用户是否点赞过”的状态。
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //鐢ㄦ埛鏈櫥褰曪紝鐩存帴杩斿洖
            return;
        }
        //鑾峰彇鐧诲綍鐢ㄦ埛
        Long userId = user.getId();
        //鍒ゆ柇鏄惁鐐硅禐
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞或取消点赞博客，并同步更新 Redis 点赞集合。
     */
    @Override
    public Result likeBlog(Long id) {
        //鑾峰彇鐢ㄦ埛id
        Long userId = UserHolder.getUser().getId();
        //鍒ゆ柇鐢ㄦ埛鏄惁宸茬粡鐐硅禐
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null){
            //濡傛灉鏈偣璧烇紝鐐硅禐鏁板姞涓€锛屽苟淇濆瓨鐢ㄦ埛鍒皉edis闆嗗悎
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //濡傛灉宸茬偣璧烇紝鐐硅禐鏁板噺涓€
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询某篇博客点赞榜前几位用户。
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //鏌ヨtop5鐐硅禐鐢ㄦ埛
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //瑙ｆ瀽鐢ㄦ埛id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //鏍规嵁鐢ㄦ埛id鏌ヨ鐢ㄦ埛骞惰浆鎹负DTO
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 发布博客，并把新动态推送到粉丝收件箱。
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 鑾峰彇鐧诲綍鐢ㄦ埛
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 淇濆瓨鎺㈠簵鍗氭枃
        boolean isSuccess = save(blog);
        if(isSuccess){
            //鏌ヨ绗旇浣滆€呯殑鎵€鏈夌矇涓?
            List<Follow> fans = followService.query().eq("follow_user_id", user.getId()).list();
            //鎺ㄩ€佺瑪璁癷d缁欐墍鏈夌矇涓?
            for (Follow fan : fans) {
                stringRedisTemplate.opsForZSet().add(FEED_KEY + fan.getUserId(), blog.getId().toString(), System.currentTimeMillis());
            }
            return Result.ok(blog.getId());
        }
        return Result.fail("绗旇淇濆瓨澶辫触");
    }

    /**
     * 查询关注流博客，使用滚动分页适配时间线场景。
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        //鏌ヨ鏀朵欢绠?ZREVRANGEBYSCORE key Max Min WITHSCO
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            log.info("娌℃湁鏌ヨ鍒板叧娉ㄨ€呯殑绗旇");
            return Result.ok(Collections.emptyList());
        }
        log.info("鏌ヨ鍒板叧娉ㄨ€呯殑绗旇锛屾暟閲忥細{}", typedTuples.size());
        //瑙ｆ瀽鏁版嵁锛歜logId銆乻core锛堟椂闂存埑锛?
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
        //鏍规嵁id鏌ヨblog
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //鏌ヨblog鏈夊叧鐨勭敤鎴?
            User user = userService.getById(blog.getUserId());
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //鏌ヨblog鏄惁琚偣璧?
            isBlogLiked(blog);
        }
        //灏佽骞惰繑鍥?
        ScrollResult r = new ScrollResult();
        log.info("blogs:{}", blogs);
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }
}

