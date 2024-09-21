package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    //微信服务接口地址（用来返回用户唯一标识的接口地址）
    public static final String WX_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    //WeChatProperties读取配置文件的属性类，封装的有2个返回唯一标识所需要的2个参数
    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired
    private UserMapper userMapper;

    /**
     * 微信登录
     * 传统的用户名密码登录：需要去查询我们自己的用户表，然后去检查用户名和密码是否正确。
     * 微信用户登录：调用之前学习的微信服务器接口，通过code授权码换取 用户唯一标识 OpenID
     *
     *
     * @param userLoginDTO
     * @return
     */
    public User wxLogin(UserLoginDTO userLoginDTO) {
        //1.调用微信接口服务，获得当前微信用户的OpenID
        String openid = getOpenid(userLoginDTO.getCode());

        //2.判断openid是否为空，如果为空表示登录失败，抛出业务异常
        if(openid == null){
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        //3.如果不为空说明这是一个合法的微信用户
        // 3.1判断当前用户是否为新用户（当前这个微信用户的openid是否在用户表里存储，如果没有代表是一个新用户）
        User user = userMapper.getByOpenid(openid);

        // 3.2如果是新用户，自动完成注册（封装到user对象，保存在用户表中）
        if(user == null){
            //现在只能获取到用户的唯一标识、注册的时间。像其他的性别 身份证号 手机号获取不到
            //   后续根据个人中心去完善业务信息。
            user = User.builder()
                    .openid(openid)
                    .createTime(LocalDateTime.now())
                    .build();
            userMapper.insert(user);//后绪步骤实现
        }

        //返回这个用户对象
        return user;
    }

    /**
     * 调用微信接口服务，获取微信用户的openid
     * 业务层发送请求调用接口，通过封装好的HttpClient的工具类HttpClientUtil实现
     * HttpClient作用：可以在java程序中通过编码的方式来发送http请求
     * @param code
     * @return
     */
    private String getOpenid(String code){ //ctrl+alt+m：选中代码抽取一个方法
        //调用微信接口服务，获得当前微信用户的openid
        Map<String, String> map = new HashMap<>();
        map.put("appid",weChatProperties.getAppid());//小程序生成的唯一标识appid，注意不是用户的唯一标识openid 配置文件属性类获取
        map.put("secret",weChatProperties.getSecret());//小程序生成的秘钥  配置文件属性类获取
        map.put("js_code",code); //授权码，通过前端传递过来的参数获取
        map.put("grant_type","authorization_code");//授权类型 固定值
        //url地址    封装的请求参数
        //返回的结果是一个json字符串
        String json = HttpClientUtil.doGet(WX_LOGIN, map);

        //将json字符串解析为json对象，通过k获取它的v
        JSONObject jsonObject = JSON.parseObject(json);
        String openid = jsonObject.getString("openid");
        return openid;
    }
}

