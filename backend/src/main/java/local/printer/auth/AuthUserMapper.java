package local.printer.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthUserMapper {
    record Account(long id, String username, String passwordHash, String role) {
        @Override public String toString() { return "Account[REDACTED]"; }
    }

    @Select("SELECT id,username,password_hash AS passwordHash,role FROM users WHERE username=#{username}")
    Account findByUsername(String username);

    @Insert("INSERT INTO users(username,password_hash,role) VALUES(#{username},#{hash},'CUSTOMER')")
    int registerCustomer(@Param("username") String username, @Param("hash") String hash);
}
