package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 添加购物车
     * 业务分析：购物车中添加了2份商品数据，那么体现在购物车表里面并不是2条数据，
     *         因为购物车表里面有一个number字段，代表的是商品的数量，也就是说
     *         如果是相同的商品只需要把它的这个数量加1就可以了。
     *
     * 总结：当我们添加购物车的时候首先需要判断一下，当前添加到购物车的这个商品它是否在购物
     *      车当中已经存在了，如果存在只需要执行一个修改操作把这个数量加1，如果不存在 在执行一个
     *      insert插入操作来插入一条数据。
     *
     *  问题：不同的用户需要有自己的购物车，通过user_id字段来体现，所以说在查询购物车中的这个商品
     *       的时候，需要把这个用户的id作为条件去查询。
     *
     *
     * @param shoppingCartDTO
     */
    @Override
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        //构造ShoppingCart封装请求参数，因为它包含用户的id。
        ShoppingCart shoppingCart = new ShoppingCart();
        //对象属性拷贝：dishId、setmealId、dishFlavor
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
        //设置用户id：用户端每次发送请求都会携带token，通过JwtTokenUserInterceptor拦截器去解析
        //         这个token,解析出来的用户id和ThreadLocal进行绑定，之后在这个地方通过ThreadLocal取出来即可。
        Long userid = BaseContext.getCurrentId();
        shoppingCart.setUserId(userid);


        /**
         * 1.判断当前加入到购物车中的商品是否已经存在了:
         *    如何判断是否存在了，需要去查询，那要查询的话是根据什么条件去查询呢？？？
         *    购物车添加的是套餐：根据套餐id和用户id，因为不同的用户有自己的购物车数据，需要通过user_id来区分出来。
         *        eg：select * from shopping_cart where user_id = ? and setmeal_id = xxx;
         *           结果有可能查出来有可能查不出来，之后按照查不出出来的情况分别判断。
         *    购物车添加的是菜品：除了菜品id、用户id 还需要根据口味数据查询，因为对于同一个菜品来说如果它的口味
         *                    不一样的话，在购物车里面也是不同的2条数据，你就不能简单地把这个数量给加1了，所以
         *                    口味也是一个查询字段。
         *       eg：select * from shopping_cart where user_id = ? and dish_id = xxx and dish_flavor = xx;
         *
         *    不需要分别写这2条sql，只需要使用一条动态的sql来动态拼接条件即可。
         *
         */
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        if (shoppingCartList != null && shoppingCartList.size() == 1) {
            //2.如果已经存在，就更新数量，数量加1
            //注意：当前方法返回值虽然是一个list集合，但是按照当前设置的这些条件来说，实际上是不可能查出来多条数据的。
            //     根据用户id再加上菜品id或者套餐id来查询，对于相同的商品只需要修改数量就可以了，不会说在重新插入一条数据。
            //     所以说按照当前这些条件去查询的话只有2种结果：查不到或者查到之后只有一条数据。
            shoppingCart = shoppingCartList.get(0);//取出来第一条数据 也是唯一的一条数据。
            shoppingCart.setNumber(shoppingCart.getNumber() + 1);//在原先的数量基础上加1，之后执行update语句。
            // update shopping_cart set number = ? where id = ?
            //因为是从数据库里面查出来的，所以cart里面一定是有id的。
            shoppingCartMapper.updateNumberById(shoppingCart);
        } else {
            //3.如果不存在，需要插入一条购物车数据
            //思路分析：ShoppingCart实体类插入数据时除了上面设置的参数，还需要name名称、价格amount、图片
            //        的路径image，这几个参数前端并没有给我们提交过来 所以自己手动查询出来。
            //   情况1：如果提交的是一个菜品，需要在菜品表里面去查询 菜品的名称 价格 和图片路径。
            //   情况2：如果提交的是一个套餐，需要在套餐表里面去查询 套餐的名称 价格 和图片路径。
            // 所以说呢我们在这个地方啊需要做什么事情啊，先来判断一下你这一次添加到购物车中的这个商品，具体是
            //     一个菜品还是一个套餐，因为只有知道了是一个菜品还是一个套餐才能知道具体去查询那个表。
            //     只需要通过获取shoppingCartDTO的菜品id或套餐id来判断是否为空，就可以知道这一次添加到
            //      购物车中的商品是菜品还是套餐。

            //4.判断当前添加到购物车的是菜品还是套餐
            Long dishId = shoppingCartDTO.getDishId();
            if (dishId != null) {
                //添加到购物车的是菜品
                //dish_id(菜品id)不为空说明添加的就是菜品，不可能是套餐因为之前说过要么添加的是菜品要么是套餐，
                //   你不可能某一次添加的购物车既是菜品又是套餐。
                Dish dish = dishMapper.getById(dishId);
                shoppingCart.setName(dish.getName());
                shoppingCart.setImage(dish.getImage());
                shoppingCart.setAmount(dish.getPrice());
            } else {
                //添加到购物车的是套餐
                //这个地方不用再判断了，因为进到了else说明这个dishId一定为空，dishId为空说明
                //    这个SetmealId一定不为空。
                Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
                shoppingCart.setName(setmeal.getName());
                shoppingCart.setImage(setmeal.getImage());
                shoppingCart.setAmount(setmeal.getPrice());
            }
            shoppingCart.setNumber(1);//设置数量，固定第一次插入就是1.
            shoppingCart.setCreateTime(LocalDateTime.now());//创建时间
            shoppingCartMapper.insert(shoppingCart);
        }
    }

    /**
     * 查看购物车
     * @return
     */
    public List<ShoppingCart> showShoppingCart() {
        //查询某个用户的购物车数据，所以需要传递一个user_id
        //获取当前这个微信用户的id
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = ShoppingCart.builder()
                .userId(userId)
                .build();
        List<ShoppingCart> list =  shoppingCartMapper.list(shoppingCart);
        return list;
    }

    /**
     * 删除购物车中一个商品
     * @param shoppingCartDTO
     */
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        //设置查询条件，查询当前登录用户的购物车数据
        shoppingCart.setUserId(BaseContext.getCurrentId());

        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);

        if(list != null && list.size() > 0){
            shoppingCart = list.get(0);

            Integer number = shoppingCart.getNumber();
            if(number == 1){
                //当前商品在购物车中的份数为1，直接删除当前记录
                shoppingCartMapper.deleteById(shoppingCart.getId());
            }else {
                //当前商品在购物车中的份数不为1，修改份数即可
                shoppingCart.setNumber(shoppingCart.getNumber() - 1);
                shoppingCartMapper.updateNumberById(shoppingCart);
            }
        }
    }

    /**
     * 清空购物车
     */
    public void cleanShoppingCart() {
        Long userId = BaseContext.getCurrentId();
        shoppingCartMapper.clean(userId);
    }
}
