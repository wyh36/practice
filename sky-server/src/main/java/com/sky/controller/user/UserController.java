package com.sky.controller.user;

import com.sky.constant.JwtClaimsConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.properties.JwtProperties;
import com.sky.result.Result;
import com.sky.service.UserService;
import com.sky.utils.JwtUtil;
import com.sky.vo.UserLoginVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user/user")
@Api(tags = "C端用户相关接口")
@Slf4j
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private JwtProperties jwtProperties; //配置文件所对应的属性配置类

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    @PostMapping("/login")
    @ApiOperation("微信登录")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO){
        //日志输出授权码的信息
        log.info("微信用户登录：{}",userLoginDTO.getCode());

        //微信登录：需要返回值，因为他需要获取用户的信息，之后通过微信用户信息生成jwt令牌。
        //如果这个方法没有抛出异常说明登陆成功了，有异常也不需要在这处理 因为有全局异常处理类。
        User user = userService.wxLogin(userLoginDTO);//后绪步骤实现

        //为微信用户生成jwt令牌
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID,user.getId());//存放的是userid.常量类的方式
        //秘钥   过期时间   存储的数据，是一个map集合类型  （通过读取配置文件所对应的属性类调用）
        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);

        //封装响应结果为vo对象：id主键值  openid用户唯一标识 token令牌 （bulid方法构建对象）
        UserLoginVO userLoginVO = UserLoginVO.builder()
                .id(user.getId()) //需要在sql中动态的获取插入的主键值
                .openid(user.getOpenid())
                .token(token)
                .build();
        //封装统一响应结果
        return Result.success(userLoginVO);
    }
}
