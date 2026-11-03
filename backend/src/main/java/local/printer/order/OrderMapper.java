package local.printer.order;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {
    String COLUMNS = "SELECT o.id,o.order_no AS orderNo,m.model_code AS modelCode,m.brand,"
        + "o.serial_no AS serialNo,o.purchase_date AS purchaseDate,o.is_demo AS orderDemo,m.is_demo AS modelDemo "
        + "FROM orders o JOIN device_models m ON m.id=o.model_id ";

    @Select("SELECT COUNT(*) FROM orders WHERE customer_id=#{customerId} AND status='ACTIVE'")
    long countOwned(long customerId);

    @Select(COLUMNS + "WHERE o.customer_id=#{customerId} AND o.status='ACTIVE' ORDER BY o.id DESC LIMIT #{size} OFFSET #{offset}")
    List<OrderResponse> listOwned(@Param("customerId") long customerId, @Param("offset") long offset, @Param("size") int size);

    @Select(COLUMNS + "WHERE o.id=#{id} AND o.customer_id=#{customerId}")
    OrderResponse findOwned(@Param("id") long id, @Param("customerId") long customerId);

    @Select(COLUMNS + "WHERE o.id=#{id} AND o.customer_id=#{customerId} AND o.status='ACTIVE' FOR UPDATE")
    OrderResponse lockActiveOwned(@Param("id") long id,@Param("customerId") long customerId);
}
