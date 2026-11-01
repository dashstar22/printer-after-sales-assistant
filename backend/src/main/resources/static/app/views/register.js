import {fragment,showText} from './shared.js';

export function registerView(state,actions) {
  const view=fragment('register-view').firstElementChild;
  const form=view.querySelector('form');
  const username=view.querySelector('#register-username');
  const password=view.querySelector('#register-password');
  const confirmation=view.querySelector('#register-password-confirmation');
  const submit=view.querySelector('#register-submit');
  let current=state;
  function validateConfirmation(){
    confirmation.setCustomValidity(confirmation.value&&confirmation.value!==password.value?'两次输入的密码不一致':'');
  }
  password.addEventListener('input',validateConfirmation);
  confirmation.addEventListener('input',validateConfirmation);
  form.addEventListener('input',()=>showText(view.querySelector('#register-feedback'),''));
  function update(value){
    current=value;
    showText(view.querySelector('#register-feedback'),value.error);
    form.querySelectorAll('input,button').forEach(control=>control.disabled=!!value.pending);
    submit.textContent=value.pending==='register'?'正在创建账号':'创建账号';
  }
  update(state);
  form.addEventListener('submit',event=>{
    event.preventDefault();validateConfirmation();
    if(current.pending||!form.reportValidity())return;
    actions.register(username.value.trim(),password.value,confirmation.value);
  });
  return {element:view,update};
}
