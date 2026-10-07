package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Data  // 自动生成getter和setter方法
@EqualsAndHashCode(callSuper = false)  // 生成equals和hashCode方法,且生成 equals() 和 hashCode() 时，不考虑父类中的字段和逻辑
@Accessors(chain = true)   // 让 setter 支持链式调用。
@TableName("tb_user")  // User 这个实体类对应数据库里的 tb_user 表。
public class User implements Serializable {
    // implements Serializable 表示这个类的实例可以被序列化，即可以被转换成字节流，以便于保存到文件或者通过网络传输。

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 手机号码
     */
    private String phone;

    /**
     * 密码，加密存储
     */
    private String password;

    /**
     * 昵称，默认是随机字符
     */
    private String nickName;

    /**
     * 用户头像
     */
    private String icon = "";

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
