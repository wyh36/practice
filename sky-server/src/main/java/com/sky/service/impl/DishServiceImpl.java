package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.Setmeal;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 菜品起售停售
     *
     * @param status
     * @param id
     */
    @Transactional
    public void startOrStop(Integer status, Long id) {
        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();
        dishMapper.update(dish);

        if (status == StatusConstant.DISABLE) {
            // 如果是停售操作，还需要将包含当前菜品的套餐也停售
            List<Long> dishIds = new ArrayList<>();
            dishIds.add(id);
            // select setmeal_id from setmeal_dish where dish_id in (?,?,?)
            List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(dishIds);
            if (setmealIds != null && setmealIds.size() > 0) {
                for (Long setmealId : setmealIds) {
                    Setmeal setmeal = Setmeal.builder()
                            .id(setmealId)
                            .status(StatusConstant.DISABLE)
                            .build();
                    setmealMapper.update(setmeal);
                }
            }
        }
    }

    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    public List<Dish> list(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        return dishMapper.list(dish);
    }

    /**
     * 新增菜品和对应的口味
     *
     * @param dishDTO
     */
    @Transactional //涉及到多个表的数据操作，所以需要保证数据的一致性
    public void saveWithFlavor(DishDTO dishDTO) {

        //1.向菜品表插入1条数据，由页面原型可知一次只能插入一条
        //  不需要把整个DishDTO传进去，因为DishDTO包含菜品和菜品口味数据，
        //   现在只需要插入菜品数据，所以这个地方只需要传递dish菜品实体对象即可
        //   通过对象属性拷贝，前提是2者属性保持一致
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.insert(dish);//后绪步骤实现

        //2.向口味表插入n条数据，一条、多条、没有
        //获取insert语句生成的主键值
        //注意：此时前端不能传递dishId属性，因为当前是新增菜品，此时这个菜品还没有添加完，
        //     这个dishId根本没有值。它是菜品插入玩自动生产的id，也就是口味表所关联的外键dishId。
        //解决：上面已经向菜品表插入了一条数据，这个dishId已经分配好了，所以可以在sql上
        //     通过useGeneratedKeys开启获取插入数据时生成的主键值，赋值给keyProperty
        //     指定的属性值id
        Long dishId = dish.getId();

        //口味数据通过实体类的对象集合属性封装的，所以需要先把集合中的数据取出来
        List<DishFlavor> flavors = dishDTO.getFlavors();
        //口味不是必须的有可能用户没有提交口味数据，所以需要判断一下
        if (flavors != null && flavors.size() > 0) {
            //用户确实提交的有口味数据，此时插入口味数据才有意义
            //有了菜单表这个主键值id，就需要为dishFlavor里面的每一个dishId（关联外键）属性赋值，
            //   所以在批量插入数据之前需要遍历这个对象集合，为里面的每个对象DishFlavor的dishId赋上值
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });

            /*
             * 口味数据flavors是一个对象类型的list集合来接收的，
             * 不需要遍历这个集合一条一条的插入数据，因为sql支持批量插入
             * 直接把这个集合对象传进去，通过动态sql标签foreach进行遍历获取。
             * */
            dishFlavorMapper.insertBatch(flavors);//后绪步骤实现
        }
    }

    @Override
    /**
     * 菜品分页查询
     *
     * @param dishPageQueryDTO
     * @return
     */
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        //需要在查询功能之前开启分页功能：当前页的页码   每页显示的条数
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        //这个方法有返回值为Page对象，里面保存的是分页之后的相关数据
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);//后绪步骤实现
        //封装到PageResult中:总记录数  当前页数据集合
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 批量删除
     *
     * @param ids
     */
    @Transactional
    public void deleteBatch(List<Long> ids) {

        //判断当前菜品是否能够删除---是否存在起售中的菜品？？
        //思路：遍历获取传入的id，根据id查询菜品dish中的status字段，0 停售 1 起售，
        //    如果是1代表是起售状态不能删除
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);//后绪步骤实现
            if (dish.getStatus().equals(StatusConstant.ENABLE)) { //常量类方式
                //当前菜品处于起售中，不能删除
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }
        //判断当前菜品是否能够删除---是否被套餐关联了？？
        //思路：菜品表 套餐表是多对多关系，它们的关系表为菜品套餐关系表setmeal_dish（菜品id 对应 套餐id）
        //     当前要删除菜品此时是知道菜品的Id的，所以可以根据这个菜品id去查询套餐id，如果能查出来说明
        //     菜品被套餐关联了，不能删除。
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && setmealIds.size() > 0) {
            //当前菜品被套餐关联了，不能删除
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        //删除菜品表中的菜品数据
        //这个地方是在业务层循环遍历一个个删除的。
        //缺点：这个地方循环遍历删除，写了2次sql性能比较差
        //解决：动态sql-foreach，只需要一条sql就可以实现批量删除。详情查看4.5
        //for (Long id : ids) {
            //dishMapper.deleteById(id);//后绪步骤实现
            //删除菜品关联的口味数据
            //思路：这个地方不需要先去查一下有没有这个口味在去删除，因为不管你有还是没有
            //     我都尝试进行删除，所以这个地方不需要在去查了。
            // 根据菜品的id去删除口味表：菜品表--》口味表 一对多，菜品的id 保存在口味表当中充当外键dish_id，
            //     删除口味表的sql条件为dish_id也就是这个传入的菜品id
            //dishFlavorMapper.deleteByDishId(id);//后绪步骤实现

        //根据菜品id集合批量删除菜品数据
        //sql:delete from dish where id in (?,?,?)
        dishMapper.deleteByIds(ids);

        //根据菜品id集合批量删除关联的口味数据
        //sql:delete from dish_flavor where dish_id in (?,?,?)
        dishFlavorMapper.deleteByDishIds(ids);
        }

    /**
     * 根据id查询菜品和对应的口味数据
     *
     * @param id
     * @return
     */
    @Override
    public DishVO getByIdWithFlavor(Long id) {
        //根据id查询菜品数据
        Dish dish = dishMapper.getById(id); //删除的时候已经写过了，所以这里直接调用方法即可

        //根据菜品id查询口味数据
        //菜品表 口味表是一对多关系，菜品表的id保存在口味表当中充当外键为dish_id
        List<DishFlavor> dishFlavors = dishFlavorMapper.getByDishId(id);//后绪步骤实现

        //将查询到的数据封装到VO
        DishVO dishVO = new DishVO();
        //通过对象拷贝的方式，把查询到的菜品数据封装到dishVO类中，
        //  注意：这个Dish类中没有categoryName分类名称属性，它不是必须的，所以这个地方拷不过来也没有关系
        //       但是点击修改页面确实回显分类名称了，它是通过这个分类的id回显得，查询菜品数据回显给前端的是
        //       分类的id，根据分类的id获取分类名称（接口已实现）进而来回显分类名称。
        BeanUtils.copyProperties(dish, dishVO);
        //通过set方法把口味数据封装到dishVO类中
        dishVO.setFlavors(dishFlavors);

        return dishVO;
    }

    /**
     * 根据id修改菜品基本信息和对应的口味信息
     *
     * 思路分析：菜品表修改直接使用update语句即可，对于这个关联的口味表，
     *         口味的修改比较复杂，因为它的情况有很多 有可能口味没写修改 有可能
     *         口味是追加的 也有可能口味是删除了，那么这个地方我们有没有一种比较
     *         简单的处理方式呢？？？
     *         可以先把你当前这个菜品原先关联的口味数据全都统一删掉，然后在按照你当前
     *         传过来的这个口味,重新再来插入一遍这个数据就可以了。
     *
     * @param dishDTO
     */
    @Override
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        //说明：DishDTO含有口味数据，当前只是修改菜品的基本信息，所以直接传递DishDTO不合适，
        //     可以把DishDTO的数据拷贝到菜品的基本信息类Dish中更合适。
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //修改菜品表基本信息
        dishMapper.update(dish);

        //删除原有的口味数据
        dishFlavorMapper.deleteByDishId(dishDTO.getId());

        //重新插入口味数据
        //口味数据通过实体类的对象集合属性封装的，所以需要先把集合中的数据取出来
        List<DishFlavor> flavors = dishDTO.getFlavors();
        //口味不是必须的有可能用户没有提交口味数据，所以需要判断一下
        if (flavors != null && flavors.size() > 0) {
            //用户确实提交的有口味数据，此时插入口味数据才有意义
            //注意：口味数据的dishId前端并不能传递，它是菜单表插入数据后自动生成的主键值，也就是
            //     口味表的关联外键dishId，有了菜单表这个主键值id，就需要为dishFlavor里面的每一个dishId（关联外键）
            //     属性赋值，所以在批量插入数据之前需要遍历这个对象集合，为里面的每个对象DishFlavor的dishId赋上值
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishDTO.getId());
            });
            //向口味表插入n条数据 (新增菜品的时候已经写过了)
            /*
             * 口味数据flavors是一个对象类型的list集合来接收的，
             * 不需要遍历这个集合一条一条的插入数据，因为sql支持批量插入
             * 直接把这个集合对象传进去，通过动态sql标签foreach进行遍历获取。
             * */
            dishFlavorMapper.insertBatch(flavors);
        }
    }



    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    public List<DishVO> listWithFlavor(Dish dish) {
        List<Dish> dishList = dishMapper.list(dish);

        List<DishVO> dishVOList = new ArrayList<>();

        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d,dishVO);

            //根据菜品id查询对应的口味
            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());

            dishVO.setFlavors(flavors);
            dishVOList.add(dishVO);
        }

        return dishVOList;
    }
}



