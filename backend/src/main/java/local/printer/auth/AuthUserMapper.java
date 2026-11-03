package local.printer.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthUserMapper {
    record Account(long id, String username, String passwordHash, String role, String status, long credentialVersion, boolean mustChangePassword,
                   boolean emailVerified,boolean recoveryActive,boolean recoveryPending) {
        public Account(long id,String username,String passwordHash,String role){this(id,username,passwordHash,role,"ACTIVE",0,false,false,false,false);}
        public Account(long id,String username,String passwordHash,String role,String status,long version,boolean change){this(id,username,passwordHash,role,status,version,change,false,false,false);}
        @Override public String toString() { return "Account[REDACTED]"; }
    }

    String COLUMNS="id,username,password_hash AS passwordHash,role,status,credential_version AS credentialVersion,must_change_password AS mustChangePassword,(email_verified_at IS NOT NULL) AS emailVerified,COALESCE(recovery_until>CURRENT_TIMESTAMP(6),FALSE) AS recoveryActive,(recovery_until IS NOT NULL) AS recoveryPending";
    @Select("SELECT "+COLUMNS+" FROM users WHERE username=#{username}")
    Account findByUsername(String username);

    @Select("SELECT "+COLUMNS+" FROM users WHERE email=#{email}")
    Account findByEmail(String email);

    @Insert("INSERT INTO users(username,password_hash,role) VALUES(#{username},#{hash},'CUSTOMER')")
    int registerCustomer(@Param("username") String username, @Param("hash") String hash);
}
