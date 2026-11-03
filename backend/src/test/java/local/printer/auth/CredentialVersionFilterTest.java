package local.printer.auth;

import jakarta.servlet.FilterChain;
import local.printer.auth.email.EmailIdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Filter boundary checks; these unit doubles do not substitute for the real browser reload. */
class CredentialVersionFilterTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void identityFormsCanLoadTheirExactSharedModuleInBothRestrictedModes() throws Exception {
        for(boolean change:new boolean[]{false,true})for(String method:new String[]{"GET","HEAD"}) {
            var response=call(method,"/repair/api.js",change,false,"ACTIVE",0,true);
            assertEquals(200,response.getStatus());
            assertEquals("continued",response.getHeader("Filter-Chain"));
        }
    }
    @Test void resourceAllowanceDoesNotMatchWritesOrSimilarPaths() throws Exception {
        for(boolean change:new boolean[]{false,true}) {
            assertBlocked(call("POST","/repair/api.js",change,false,"ACTIVE",0,true),403,"EMAIL_BIND_REQUIRED");
            for(String path:new String[]{"/repair/api.js/other","/repair/api.js.map","/repair/api.js%2fother","/repair/index.html"})
                assertBlocked(call("GET",path,change,false,"ACTIVE",0,true),403,"EMAIL_BIND_REQUIRED");
        }
    }
    @Test void bothBusinessGatesRemainEnforced() throws Exception {
        for(String path:new String[]{"/api/orders","/api/tickets","/api/admin/accounts","/api/conversations"}) {
            assertBlocked(call("GET",path,false,false,"ACTIVE",0,true),403,"EMAIL_BIND_REQUIRED");
            assertBlocked(call("GET",path,true,true,"ACTIVE",0,true),403,"PASSWORD_CHANGE_REQUIRED");
        }
    }
    @Test void staticModuleDoesNotBypassAccountOrCredentialRevocation() throws Exception {
        assertBlocked(call("GET","/repair/api.js",false,false,"DISABLED",0,true),401,"UNAUTHENTICATED");
        assertBlocked(call("GET","/repair/api.js",false,false,"ACTIVE",1,true),401,"UNAUTHENTICATED");
        assertBlocked(call("GET","/repair/api.js",false,false,"ACTIVE",0,false),401,"UNAUTHENTICATED");
    }
    private MockHttpServletResponse call(String method,String path,boolean change,boolean verified,String status,long version,boolean migration) throws Exception {
        var original=new AuthUserMapper.Account(42,"fixture_identity","{noop}unused","CUSTOMER","ACTIVE",0,change,verified,false,false);
        var current=new AuthUserMapper.Account(42,"fixture_identity","{noop}unused","CUSTOMER",status,version,change,verified,false,false);
        var user=new SessionUser(original,true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()));
        var users=mock(AuthUserMapper.class);when(users.findByUsername("fixture_identity")).thenReturn(current);
        var emails=mock(EmailIdentityService.class);when(emails.migrationEnabled()).thenReturn(migration);
        var request=new MockHttpServletRequest(method,path);request.setServletPath(path);
        var response=new MockHttpServletResponse();
        FilterChain chain=(req,res)->((MockHttpServletResponse)res).setHeader("Filter-Chain","continued");
        new CredentialVersionFilter(users,emails).doFilter(request,response,chain);
        return response;
    }
    private void assertBlocked(MockHttpServletResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus());assertNull(response.getHeader("Filter-Chain"));assertTrue(response.getContentAsString().contains(code));
    }
}
