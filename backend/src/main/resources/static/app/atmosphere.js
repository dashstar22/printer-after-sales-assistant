// Ambient motion stays on at a low intensity; system reduced-motion preferences take priority.
export async function startAtmosphere() {
  const reduced=matchMedia('(prefers-reduced-motion: reduce)');
  const small=matchMedia('(max-width: 760px)');
  const containers=[];
  let visible=true;
  function sync(){
    const stopped=reduced.matches||document.hidden||!visible;
    for(const field of containers)stopped?field.pause():field.play();
  }
  function options(near){
    return {autoPlay:false,fullScreen:{enable:false},detectRetina:false,fpsLimit:24,pauseOnBlur:true,pauseOnOutsideViewport:true,
      particles:{number:{value:small.matches?(near?5:18):(near?10:36),density:{enable:false}},
        paint:{color:{value:['#d7c2ff','#b79ae4']}},shape:{type:'circle'},size:{value:near?{min:1,max:1.5}:{min:.4,max:.8}},
        opacity:{value:{min:.08,max:near?.22:.16}},
        move:{enable:!reduced.matches,direction:'top-right',speed:near?.12:.06,outModes:{default:'out'}}}};
  }
  async function reset(){await Promise.all(containers.map((field,i)=>field.reset(options(i===1))));for(const field of containers)field.draw(true);sync();}
  try{
    if(window.tsParticles&&window.loadBasic){
      await window.loadBasic(window.tsParticles);
      containers.push(...(await Promise.all([window.tsParticles.load({id:'particles-far',options:options(false)}),window.tsParticles.load({id:'particles-near',options:options(true)})])).filter(Boolean));
      for(const field of containers)field.draw(true);sync();
    }
  }catch{/* The service remains usable if decorative dependencies fail. */}
  reduced.addEventListener('change',reset);small.addEventListener('change',reset);
  document.addEventListener('visibilitychange',sync);
  new IntersectionObserver(entries=>{visible=entries[0]?.isIntersecting??true;sync();},{threshold:.01}).observe(document.getElementById('scene'));
  return Object.freeze({revealEntry(){}});
}
