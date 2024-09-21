package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.websocket.WebSocketServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private WebSocketServer webSocketServer;
    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    public OrderSubmitVO SubmitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        /**
         * 1.异常情况的处理（收货地址为空、购物车为空）
         *   绝大部分情况下不做这个判断处理问题也不大，因为如果是小程序提交过来的请求
         *   其实在小程序那端也做了判断（收货地址为空、购物车为空也是不能提交数据的），
         *   但是为了代码的健壮性建议在后端还是多次判断一下，因为用户如果并不是通过
         *   小程序提交的而是通过其它的一些方式 比如postman来提交这些请求，这个时候
         *   是没有任何校验的，此时后端在不校验那再处理的时候可能就会出现各种问题。
         */
        //1.1 通过前端传递过来的地址簿id查询数据库是否有收货地址，如果查不到则抛出异常。
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            //抛出业务异常
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        //1.2 查询当前用户的购物车数据（购物车为空也不能正常下单）
        Long userId = BaseContext.getCurrentId();//获取当前用户的id
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            //抛出业务异常
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //2.向订单表插入1条数据（用户不管买多少个商品，只要它提交就是一个订单，对应一条订单数据）
        //构造订单数据
        Orders order = new Orders();
        //OrdersSubmitDTO已经封装好了一些数据，所以进行一个对象的拷贝
        BeanUtils.copyProperties(ordersSubmitDTO,order);
        //设置剩余的参数：
        //用户的手机号，dto并没有给我们传递过来，通过地址簿id查询出地址数据，在地址数据中就包含用户的名字和手机号
        //    在前面异常判断中已经查过了，所以在这个地方直接取就可以。
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());//收货人
        //要求是字符串类型，这个地方返回的是Long类型，所以需要进行转化
        order.setNumber(String.valueOf(System.currentTimeMillis()));//订单号，使用当前系统时间的时间戳生成
        order.setUserId(userId);//当前订单是属于哪个用户的
        order.setStatus(Orders.PENDING_PAYMENT);//订单状态：此时是待付款
        order.setPayStatus(Orders.UN_PAID);//支付状态，用户刚完成下单所以是未支付状态
        order.setOrderTime(LocalDateTime.now());//下单时间

        //这个sql需要返回插入的主键值，在后面插入订单明细，在订单明细实体类中会使用当前这个订单的id
        orderMapper.insert(order);

        //3.向订单明细表插入n条数据（可能是一条也可能是多条）
        //     具体需要插入多少条数据，是由购物车中的商品决定的，因为前面做需求分析的时候
        //     提到了我们真正下单购买这些商品其实是由购物车里面的这些数据决定的，所以订单明细
        //     里面的数据如何封装就应该看购物车中的数据。
        //  购物车中的数据在前面异常处理的时候已经查过了，直接遍历购物车数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            //一条购物车数据对应就需要封装成一个订单明细对象
            OrderDetail orderDetail = new OrderDetail();
            //购物车实体类和订单明细实体类中的属性名相同，所以直接使用对象属性拷贝来封装。
            BeanUtils.copyProperties(cart, orderDetail);
            //设置当前订单明细关联的订单id，订单插入生成的主键值，动态sql封装到了order的id属性上。
            orderDetail.setOrderId(order.getId());

            //方式一：单条数据插入，遍历一次插入一次
            //方式二：批量插入，效率更高，所以这里把获得的订单明细数据给它放在list集合里面，然后一次性的批量插入。
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);//批量插入

        //4.清理当前用户的购物车中的数据（用户下单成功后，用户的这些购物车中的数据就不需要了）
        shoppingCartMapper.clean(userId);//前面购物车模块已实现

        //5.封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .build();

        return orderSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                new BigDecimal(0.01), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     * @param outTradeNo
     */
    @Override
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();
        orderMapper.update(orders);

        //通过webSocketServer向客户端浏览器推送消息
        //要求推送的消息格式是json类型，并且包含3个字段（type、orderId、content）
        Map map = new HashMap();
        map.put("type", 1);//消息类型，1表示来单提醒
        map.put("orderId", orders.getId()); //订单的id
        map.put("content", "订单号：" + outTradeNo);//订单号

        //转化为json格式
        String json = JSON.toJSONString(map);
        //通过WebSocket实现来单提醒，向客户端浏览器推送消息(调用WebSocketServer类中群发的方法)
        webSocketServer.sendToAllClient(json);
    }

    /**
     * 用户催单
     *
     * @param id
     */
    public void reminder(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在
        if (ordersDB == null ) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //基于WebSocket实现催单
        Map map = new HashMap();
        map.put("type", 2);//1表示来电提醒 2代表用户催单
        map.put("orderId", id);//订单的id
        map.put("content", "订单号：" + ordersDB.getNumber());//订单号

        //参数需要json类型，所以需要进行转化(调用WebSocketServer组件中群发的方法)
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }


}
