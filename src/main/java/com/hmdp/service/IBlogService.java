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
     * 发布保存探店博文
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 查询热门博文
     * @param current
     * @return
     */
    Result queryHotBlog(int current);

    /**
     * 查询博文详情
     */
    Result queryBlogById(Long id);
    /**
     * 点赞
     * @param id
     * @return
     */
    Result likeBlog(Long id);
    /**
     * 点赞人排行榜top5
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);
}
