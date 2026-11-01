package local.printer.seed;

import java.time.LocalDate;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SeedMapper {
    record User(long id, String username, String passwordHash, String role) {
        @Override public String toString() { return "User[REDACTED]"; }
    }
    record Model(long id, String modelCode, String brand, boolean demo) {}
    record Order(long id, String orderNo, long customerId, long modelId,
                 String serialNo, LocalDate purchaseDate, boolean demo) {}

    @Select("SELECT id,username,password_hash AS passwordHash,role FROM users WHERE username=#{name} FOR UPDATE")
    User user(String name);
    @Insert("INSERT INTO users(username,password_hash,role) VALUES(#{name},#{hash},#{role})")
    int insertUser(@Param("name") String name, @Param("hash") String hash, @Param("role") String role);
    @Select("SELECT id,model_code AS modelCode,brand,is_demo AS demo FROM device_models WHERE model_code=#{code} FOR UPDATE")
    Model model(String code);
    @Insert("INSERT INTO device_models(model_code,brand,is_demo) VALUES(#{code},'Brother',true)")
    int insertModel(String code);
    @Select("SELECT id,order_no AS orderNo,customer_id AS customerId,model_id AS modelId,serial_no AS serialNo,purchase_date AS purchaseDate,is_demo AS demo FROM orders WHERE order_no=#{number} FOR UPDATE")
    Order order(String number);
    @Insert("INSERT INTO orders(order_no,customer_id,model_id,serial_no,purchase_date,is_demo) VALUES(#{number},#{customer},#{model},#{serial},#{date},true)")
    int insertOrder(@Param("number") String number, @Param("customer") long customer,
                    @Param("model") long model, @Param("serial") String serial, @Param("date") LocalDate date);
}
