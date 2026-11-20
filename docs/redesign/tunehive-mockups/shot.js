const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:1536,height:1100},deviceScaleFactor:1.5});
await p.goto('file://'+process.cwd()+'/mockups.html');await p.waitForTimeout(500);
const names={s1:'01-identic',s2:'02-library-add-playlist',s3:'03-equalizer-queue-settings'};
for(const id of Object.keys(names)){await (await p.$('#'+id)).screenshot({path:names[id]+'.png'});}
await b.close()})();
