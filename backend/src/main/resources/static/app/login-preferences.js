// Only the login preference is stored here. Credentials belong to the browser password manager.
const KEY='printer.login.preferences.v1';
export function loginPreferences(){
  try{
    const value=JSON.parse(localStorage.getItem(KEY));
    const autoLogin=value?.autoLogin===true;
    // Migrate the old account-only preference without retaining a separate copy of the email.
    if(value&&(Object.hasOwn(value,'username')||Object.hasOwn(value,'remember')))saveLoginPreferences(autoLogin);
    return {autoLogin};
  }catch{return {autoLogin:false};}
}
export function saveLoginPreferences(autoLogin){
  try{
    if(autoLogin)localStorage.setItem(KEY,JSON.stringify({autoLogin:true}));
    else localStorage.removeItem(KEY);
  }catch{/* Storage restrictions do not prevent login. */}
}
export function clearAutoLoginPreference(){
  saveLoginPreferences(false);
}
