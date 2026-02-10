package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    /**
     * 查询热门博文
     * @param current 当前页
     * @return 热门博文
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询博文
     * @param id 博文id
     * @return 博文
     */
    Result queryByBlogId(Long id);

    /**
     * 点赞博文
     * @param id 博文id
     * @return 结果
     */
    Result likeBlog(Long id);

    /**
     * 查询博文点赞数量和状态
     * @param id 博文id
     * @return 结果
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存博文
     * @param blog 博文信息
     * @return 结果
     */
    Result saveBlog(Blog blog);

    /**
     * 查询关注者的博文
     * @param max 时间戳
     * @param offset 偏移量
     * @return 结果
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
