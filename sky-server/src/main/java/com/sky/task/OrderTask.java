package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * 自定义定时任务，实现订单状态定时处理
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理支付超时订单的方法
     */
    @Scheduled(cron = "0 * * * * ?")//每分钟触发一次
    public void processTimeoutOrder(){
        log.info("处理支付超时订单：{}", new Date());

        /**
         * 查询订单表中的那些订单是超时了，查询需要那些条件呢？？？
         *     1.首先订单状态需要处于“待付款”状态。
         *     2.下单的时间超过了15分钟：下单时间 < 当前时间-15分钟
         *  select * from orders where status = 1 and order_time < 当前时间-15分钟
         *
         *  LocalDateTime.now():当前时间 ，新的时间日期API
         *  plusMinutes(xx)：加xxx分钟
         *  plusMinutes(-15)：加上一个负的就是减15分钟
         */
        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);//计算出下单时间

        //查询到的超时订单集合
        List<Orders> ordersList  = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, time);
        //判断是否或取到超时订单集合
        if(ordersList != null && ordersList.size() > 0) {
            //遍历订单集合，之后修改每个订单的状态
            for (Orders orders : ordersList) {  //快捷键：ordersList.for
                orders.setStatus(Orders.CANCELLED); //订单状态：6已取消
                orders.setCancelReason("支付超时，自动取消");//取消原因
                orders.setCancelTime(LocalDateTime.now());//订单取消时间
                orderMapper.update(orders); //之前实现过了
            }
        }
    }

    /**
     * 处理一直处于“派送中”状态的订单
     */
    @Scheduled(cron = "0 0 1 * * ?")//每天凌晨1点触发一次
    public void processDeliveryOrder(){
        log.info("处理派送中订单：{}", new Date());

        /**
         * select * from orders where status = 4 and order_time < 当前时间-1小时
         * 和上面那个方法的业务逻辑相同，只不过是查询的条件不同：
         *    1.首先订单状态需要处于“派送中”状态。
         *    2.查询的时间是上一个工作日的：下单时间 < 当前时间（每天凌晨1点）-1小时
         */
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);//计算出下单时间
        //查询到的超时订单集合
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);
        //判断是否或取到超时订单集合
        if(ordersList != null && ordersList.size() > 0){
            //遍历订单集合，之后修改每个订单的状态
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.CANCELLED); //订单状态：5已完成
                orderMapper.update(orders); //之前实现过了
            }
        }
    }


}
