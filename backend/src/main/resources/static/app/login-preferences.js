// Convenience preferences only. Authentication is always restored by the server.
const KEY='printer.login.preferences.v1';
export function loginPreferences(){
  try{
    const value=JSON.parse(localStorage.getItem(KEY));
    return {remember:value?.remember===true,autoLogin:value?.autoLogin===true,
      username:value?.remember===true&&typeof value.username==='string'?value.username.slice(0,100):''};
  }catch{return {remember:false,autoLogin:false,username:''};}
}
export function saveLoginPreferences(username,remember,autoLogin){
  try{
    if(remember||autoLogin)localStorage.setItem(KEY,JSON.stringify({remember:!!remember,autoLogin:!!autoLogin,username:remember?username:''}));
    else localStorage.removeItem(KEY);
  }catch{/* Storage restrictions do not prevent login. */}
}
export function clearAutoLoginPreference(){
  const value=loginPreferences();saveLoginPreferences(value.username,value.remember,false);
}
