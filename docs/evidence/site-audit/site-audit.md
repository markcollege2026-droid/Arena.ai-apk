# Live site audit

Run: https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/45847914411
UTC: 2026-11-26 15:20:25

| Probe | HTTP | Requested | Effective |
| --- | --- | --- | --- |
| main-root | 200 | https://arena.ai/ | https://arena.ai/ |
| main-agent | 200 | https://arena.ai/agent | https://arena.ai/agent |
| main-code | 200 | https://arena.ai/code | https://arena.ai/code |
| main-leaderboard | 403 | https://arena.ai/leaderboard | https://arena.ai/leaderboard |
| main-agent-leaderboard | 403 | https://arena.ai/leaderboard/agent | https://arena.ai/leaderboard/agent |
| main-search | 200 | https://arena.ai/history/search | https://arena.ai/history/search |
| main-terms | 200 | https://arena.ai/terms-of-use | https://help.arena.ai/articles/5629909088-terms-of-use |
| main-privacy | 200 | https://arena.ai/privacy-policy | https://help.arena.ai/articles/3765052346-privacy-policy |
| main-changelog | 200 | https://arena.ai/company/product-changelog | https://arena.ai/company/product-changelog |
| main-about | 200 | https://arena.ai/about | https://arena.ai/company/about |
| main-blog | 200 | https://arena.ai/blog | https://arena.ai/blog |
| main-blog-connectors | 200 | https://arena.ai/blog/connectors-in-agent-mode | https://arena.ai/blog/connectors-in-agent-mode |
| main-blog-coding | 200 | https://arena.ai/blog/coding-in-agent-mode | https://arena.ai/blog/coding-in-agent-mode |
| main-cms | 200 | https://arena.ai/cms/ | https://arena.ai/blog |
| host-www | 200 | https://www.arena.ai/ | https://arena.ai/ |
| host-auth | 404 | https://auth.arena.ai/ | https://auth.arena.ai/ |
| host-cdn | 404 | https://cdn.arena.ai/ | https://cdn.arena.ai/ |
| host-agent | 200 | https://agent.arena.ai/ | https://arena.ai/agent |
| host-code | 200 | https://code.arena.ai/ | https://arena.ai/code |
| host-indexable | 404 | https://indexable.arena.ai/ | https://indexable.arena.ai/ |
| host-blog | 522 | https://blog.arena.ai/ | https://blog.arena.ai/ |
| host-news | 522 | https://news.arena.ai/ | https://news.arena.ai/ |
| host-help | 200 | https://help.arena.ai/ | https://help.arena.ai/ |
| host-status | 200 | https://status.arena.ai/ | https://status.arena.ai/ |
| host-trust | 403 | https://trust.arena.ai/ | https://trust.arena.ai/ |
| docs-connectors | 200 | https://help.arena.ai/articles/6934758149-connect-your-apps-to-arena | https://help.arena.ai/articles/6934758149-connect-your-apps-to-arena |
| docs-agent | 200 | https://help.arena.ai/articles/5432423882-how-to-use-agent-mode | https://help.arena.ai/articles/5432423882-how-to-use-agent-mode |
| docs-coding | 200 | https://help.arena.ai/articles/1655691990-how-to-use-coding-in-agent-mode | https://help.arena.ai/articles/1655691990-how-to-use-coding-in-agent-mode |
| docs-account | 200 | https://help.arena.ai/articles/7258407731-arena-how-to-create-an-account | https://help.arena.ai/articles/7258407731-arena-how-to-create-an-account |
| oauth-google | 200 | https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=example.invalid&redirect_uri=https%3A%2F%2Farena.ai%2F | https://accounts.google.com/signin/oauth/error?authError=Cg9pbnZhbGlkX3JlcXVlc3QSIU1pc3NpbmcgcmVxdWlyZWQgcGFyYW1ldGVyOiBzY29wZRo3aHR0cHM6Ly9kZXZlbG9wZXJzLmdvb2dsZS5jb20vaWRlbnRpdHkvcHJvdG9jb2xzL29hdXRoMiCQAw&flowName=GeneralOAuthFlow&client_id=example.invalid&as=S-1539758408%3A1795706379607058&aes=AYsA2T1t8YatoJsXRk2psKE3xII0 |
| oauth-github | 200 | https://github.com/login/oauth/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F | https://github.com/login?client_id=example&return_to=%2Flogin%2Foauth%2Fauthorize%3Fclient_id%3Dexample%26redirect_uri%3Dhttps%253A%252F%252Farena.ai%252F%26response_type%3Dcode |
| oauth-notion | 200 | https://api.notion.com/v1/oauth/authorize?owner=user&response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F | https://app.notion.com/install-integration?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F&owner=user |
| oauth-linear | 200 | https://linear.app/oauth/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F | https://linear.app/oauth/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F |
| oauth-supabase | 400 | https://api.supabase.com/v1/oauth/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F | https://api.supabase.com/v1/oauth/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F |
| oauth-vercel | 200 | https://vercel.com/oauth/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F | https://vercel.com/oauth/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F |
| oauth-dropbox | 200 | https://www.dropbox.com/oauth2/authorize?response_type=code&client_id=example&redirect_uri=https%3A%2F%2Farena.ai%2F | https://www.dropbox.com/oauth2/authorize_error?client_id=example&error_detail=Invalid+client_id%3A+%22example%22.&error_name=unknown_client_id |
| link-jobs | 200 | https://jobs.ashbyhq.com/arena | https://jobs.ashbyhq.com/arena |
| link-discord | 200 | https://discord.com/invite/LMArena | https://discord.com/invite/LMArena |
| link-youtube | 200 | https://www.youtube.com/watch?v=79sSH0Y8FA4 | https://www.youtube.com/watch?v=79sSH0Y8FA4 |
| link-x | 403 | https://x.com/arena | https://x.com/arena |

## Script references to OAuth/connector hosts
```text
"connector_prompt_clicked",params:{prompt_id:e,connector_slug:r,source:"landing"}})},"N",0,function(e,r){t.analytics.capture({action:"connector_toggled",params:{slug:e,enabled:r}})},"D",0,function(e){return 0===e.len
),{disconnectGithub:y,isDisconnecting:S}=(0,eE.f)(),N=(0,ex.H)(),k=(0,ew.f)(),C=(0,j.r)("coding.configMenu"),E=(0,j.r)("connectors.row");_[0]!==k||_[1]!==N?(e=()=>{eg.analytics.capture({action:"coding_github_connect_clicked",params:{surface:"config_menu"}}),N.mutate(void 0,{onError:e=>(0,e
){if(!n){if("u"<typeof crypto||!crypto.getRandomValues)throw Error("crypto.getRandomValues() not supported. See https://github.com/uuidjs/uuid#getrandomvalues-not-supported");n=crypto.getRandomValues.bind(crypto)}return n(o)}},953276,(e,t,r)=>{function n(e,t){return e<<t|e>>>32-t}Object.de
){if(!t){if("u"<typeof crypto||!crypto.getRandomValues)throw Error("crypto.getRandomValues() not supported. See https://github.com/uuidjs/uuid#getrandomvalues-not-supported");t=crypto.getRandomValues.bind(crypto)}return t(r)}let a=[];for(let e=0;e<256;++e)a.push((e+256).toString(16).slice(
,t){return e.length===t.length&&e.every((e,r)=>e===t[r])}Object.defineProperty(r,"__esModule",{value:!0}),r.MAX_ENABLED_CONNECTORS_PER_TURN=void 0,r.parseEnabledConnectorsState=function(e){let t="object"==typeof e&&null!==e?e:{};return{enabledSlugs:n(t.enabledSlugs),acknowledgedSlugs:n(t.a
0,ex.$)(),m=(0,eN.o)(tg),h=o||u;return n[0]!==c||n[1]!==l||n[2]!==i||n[3]!==m||n[4]!==r?(s=e=>{l?(i(r.prompt,e),m()):c("connector")},n[0]=c,n[1]=l,n[2]=i,n[3]=m,n[4]=r,n[5]=s):s=n[5],n[6]!==r||n[7]!==h||n[8]!==s?(a=(0,t.jsx)(tf,{suggestion:r,disabled:h,onClick:s}),n[6]=r,n[7]=h,n[8]=s,n[9
>eM.current?.click(),maxSizeLabel:eQ("maxFileSize",{size:s.MAX_AGENT_UPLOAD_BYTES/1048576}),disabled:tf,isDragActive:ez,connectorsEnabled:D}),(0,t.jsx)(T.f,{value:"work"}),e7&&!tn?(0,t.jsx)(P,{value:e8,onChange:ta,disabled:tf}):null]}),rightActions:(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx
CLIENT_INTERACTION_FIELD]:e,clientPlatform:(0,n.clientPlatformFromUserAgent)(navigator.userAgent),...U.length>0?{enabledConnectors:U}:{},...e5?{modelId:e5}:{}})}),l(t.ok?"success":"error"),en.f.milestone(h,"create_response_headers"),s=en.f.startPhase(h,"create_json"),!t.ok){let e=await (0,
Item(r(e));if(null==n)return null;let i=JSON.parse(n);if("object"!=typeof i||null==i)return null;return(0,t.parseEnabledConnectorsState)(i)}catch{return null}}let i=(0,e.i(491117).$)((e,i)=>{let a=t=>{e(t);var n=i().userId,a={enabledSlugs:i().enabledSlugs,acknowledgedSlugs:i().acknowledged
LOG:e.i(123164).NEXTJS_URLS.blog,DISCORD:"https://discord.gg/arena-ai",X_PLATFORM:"https://x.com/arena",GITHUB:"https://github.com/lmarena",LINKEDIN:"https://www.linkedin.com/company/arenaai/",YOUTUBE:"https://www.youtube.com/@ArenaAIOfficial"};e.s(["f",0,t])},264483,e=>{var t=e.i(472141),
Please report this to https://github.com/markedjs/marked.`,e){let e="<p>An error occurred:</p><pre>"+di(n.message+"",!0)+"</pre>";return t?Promise.resolve(e):e}if(t)return Promise.reject(n);throw n}}}
Rive\'s canvas based web api.","main":"rive.js","homepage":"https://rive.app","repository":{"type":"git","url":"https://github.com/rive-app/rive-wasm/tree/master/js"},"keywords":["rive","animation"],"author":"Rive","contributors":["Luigi Rosso <luigi@rive.app> (https://rive.app)","Maxwell 
[2],o=s[3]),(0,n.useEffect)(a,o)}])},505280,e=>{var t=e.i(696057);e.s(["W",0,function(e,r){t.analytics.capture({action:"connector_connect_completed",params:{slug:e,outcome:r}})},"w",0,function(e){t.analytics.capture({action:"connector_connect_started",params:{slug:e}})},"x",0,function(e){
formFromUserAgent)(navigator.userAgent),timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,...U.length>0?{enabledConnectors:U}:{},...e5?{modelId:e5}:{},...e7&&"automatic"!==e8?{harnessId:e8}:{}})})}catch(e){throw c("error"),e}return c(i.ok?"success":"error"),en.f.milestone(l,"create
gth?{}:{has_connectors_enabled:!0,enabled_connector_count:e.length,enabled_connector_slugs:[...e]}}])},869505,e=>{var t=e.i(753580);function r(e){return`agent-enabled-connectors:${e}`}function n(e){if(null==e)return null;try{let n=window.localS
i.hooks.postprocess(n)),n}catch(e){return l(e)}}}onError(e,t){return n=>{if(n.message+="\nPlease report this to https://github.com/markedjs/marked.",e){let e="<p>An error occurred:</p><pre>"+er(n.message+"",!0)+"</pre>";return t?Promise.resolve(e):e}if(t)return Promise.reject(n);throw n}}}
ion s(){let e=await o(),t={mediation:"required",identity:{context:"signin",mode:"active",providers:[{configURL:"https://accounts.google.com/gsi/fedcm/config/active.json",clientId:"899073161779-tneq1jnh66s8kfvckhedkhp8kvkj8lnp.apps.googleusercontent.com",nonce:e.hashed,fields:["name","email","pictur
ncludes(t))];return i(a,e.acknowledgedSlugs)&&i(o,e.enabledSlugs)?e:{enabledSlugs:o,acknowledgedSlugs:a}},r.MAX_ENABLED_CONNECTORS_PER_TURN=50},288711,(e,t,r)=>{Object.defineProperty(r,"__esModule",{value:!0}),r.PULSE_RATE_LIMIT_FALLBACK_HOLD_MS=void 0,r.pulseHoldDeadline=function(e,t){ret
none disabled:opacity-50";function tf(e){let s,a,n,r,i,o,l=(0,d.c)(17),{suggestion:c,disabled:u,onClick:m}=e,h=(0,j.r)("connectors.promptSuggestions");return l[0]===Symbol.for("react.memo_cache_sentinel")?(s=(0,v.c)(tn.c,th),l[0]=s):s=l[0],l[1]!==m||l[2]!==c.id||l[3]!==c.slug?(a=e=>{(0,tl.
open}function tx(e){let s,a,n,r,i=(0,d.c)(13),{suggestion:o,onSelect:l,disabled:c}=e,u=o.slug;i[0]!==o.slug?(s=(0,ta.getConnectorLabel)(o.slug),i[0]=o.slug,i[1]=s):s=i[1],i[2]!==o.slug||i[3]!==s?(a={slug:u,displayName:s,description:"",authStatus:"disconnected"},i[2]=o.slug,i[3]=s,i[4]=a):
r=i[12],r}function tb(e){let s,a,n,r,i,o,l,c,u,m,h,f,p=(0,d.c)(23),{onSelect:g,disabled:x}=e,b=void 0!==x&&x,_=(0,j.r)("connectors.promptSuggestions"),[y,S]=(0,k.useState)(!1),N=(0,td.r)();p[0]!==N?(s=()=>{N()},a=[N],p[0]=N,p[1]=s,p[2]=a):(s=p[1],a=p[2]),(0,k.useEffect)(s,a),p[3]===Symbol.
rsions=[])).push({version:"3.38.1",mode:"global",copyright:"© 2014-2024 Denis Pushkarev (zloirock.ru)",license:"https://github.com/zloirock/core-js/blob/v3.38.1/LICENSE",source:"https://github.com/zloirock/core-js"})}),nt=function(t,e){return rt[t]||(rt[t]=e||{})},ot=Object,it=function(t){
stringify(a))}catch{}};return{enabledSlugs:[],acknowledgedSlugs:[],userId:null,hydrated:!1,overlayDismissNonce:0,dismissConnectorOverlays:()=>e({overlayDismissNonce:i().overlayDismissNonce+1}),connectingSlugs:[],markConnecting:t=>{i().connectingSlugs.includes(t)||e({connectingSlugs:[...i(
t.analytics.capture({action:"connector_disconnected",params:{slug:e}})},"R",0,function(){t.analytics.capture({action:"connector_explore_opened",params:{}})},"E",0,function(){t.analytics.capture({action:"connector_menu_opened",params:{}})},"m",0,function(e,r){t.analytics.capture({action:
trol","treatment-1"])),"agent-web-battles-v2":s(t.enum(["control","treatment-1","treatment-2","treatment-3"])),"disable-connectors-russia":s(o.z.boolean())},c=n.z.object(u).parse({});e.s(["Q",0,c,"p",0,u,"k",0,function(e){return null!=e&&"control"!==e}],861544)},109485,11518,e=>{var t=e.i(
```

## Connector hand-off / window.open references
```text
ailed to open",t,{flow:e?.type??"home",status:t instanceof N.P?t.status:void 0}),u(!0),o(null)}},i[0]=e):e=i[0];let p=e,m=(0,c.d)();i[1]!==m?(t=async()=>{let e=window.open("about:blank","_blank");e&&(e.opener=null),o("home"),u(!1);try{let t=await (0,L.e)();if(e){let n;e.location.href=t,ec?.(),n=()=>{(()=>{m.fetchQuery({...(0,L.Q)({fresh:!0}),staleTime:0}).catch(eu)})()
nish(h??p,"stopped"));eH(async()=>{let t,s=()=>{};en.f.milestone(h,"create_request_started");let l=en.f.startPhase(h,"create_request");try{if(t=await (0,tz.f)("/api/coding-agent/sessions",{method:"POST",headers:{"Content-Type":"application/json"},credentials:"same-origin",body:JSON.stringify({repoId:ej,repoOwner:i,repoName:o,baseBranch:tr,message:f,...x.length>0?{uploa
ogle/popup-complete","D",0,function(){let e=Math.max(0,window.screenX+(window.outerWidth-500)/2),t=Math.max(0,window.screenY+(window.outerHeight-650)/2);return window.open("about:blank","arena-google-sign-in",`popup,width=500,height=650,left=${e},top=${t}`)},"E",0,function(e){return new Promise(t=>{let r=new BroadcastChannel("arena-google-sign-in-popup"),i=()=>{clearIn
tor.commands.extendMarkRange(t.type.name)),t.openOnClick){var s,l;let n=oC(e.state,t.type.name),r=null!=(s=i.href)?s:n.href,a=null!=(l=i.target)?l:n.target;r&&(window.open(r,a),o=!0)}return o}}}))),this.options.linkOnPaste&&r.push((n={editor:this.editor,defaultProtocol:this.options.defaultProtocol,type:this.type,shouldAutoLink:this.options.shouldAutoLink},new e4({key:n
scripts discovered: 60, fetched: 60
```

## First-party API route literals
```text
"/api/"
"/api/coding-agent/sessions"
"/api/early_access_features/?token="
"/api/me"
"/api/product_tours/?token="
"/api/surveys/?token="
"/api/web_experiments/?token="
"/images/cta/arena-api.webp"
"/leaderboard/code/webdev/reference-based-design"
"/leaderboard/image-edit/commercial-design"
"/leaderboard/image-edit/multi-image-edit/commercial-design"
"/leaderboard/image/image-edit/multi-image-commercial-design"
"/leaderboard/image/image-edit/single-image-commercial-design"
"/leaderboard/image/text-to-image/commercial-design"
"/leaderboard/text-to-image/commercial-design"
"/nextjs-api/"
"/nextjs-api/auto-modality"
"/nextjs-api/autoeval/release-banner"
"/nextjs-api/bootstrap-user"
"/nextjs-api/model-catalog"
"/nextjs-api/resend-verification"
"/nextjs-api/reset-password/change"
"/nextjs-api/reset-password/confirm"
"/nextjs-api/reset-password/request"
"/nextjs-api/sign-in/email"
"/nextjs-api/sign-in/google/one-tap"
"/nextjs-api/sign-in/google/popup-complete"
"/nextjs-api/sign-up/magic-link"
"/nextjs-api/stream/create-chat"
"/nextjs-api/stream/create-evaluation"
```
