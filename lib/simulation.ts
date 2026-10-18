export type Hardware={channel:number,battery:number,lat:number,lon:number,rssi:number,state:string,heading:number,pressure:number,acceleration:number,antenna:boolean};
export const initialHardware:Hardware={channel:1,battery:89,lat:42.2711,lon:-89.094, rssi:-92,state:'IDLE',heading:270,pressure:1013,acceleration:0,antenna:true};
export function respond(input:string,h:Hardware){
 const q=input.toLowerCase().trim(); const m=q.match(/^(?:please\s+)?(?:switch|change|set|go)(?:\s+\w+){0,3}\s+channel\s+(\d+)[.!?]?$/);
 if(m){const channel=Number(m[1]);if(channel<1||channel>16)return {intent:'clarify',text:'Choose a channel from 1 to 16. Nothing was changed.'};return {intent:'set_channel',args:{channel},text:`Channel ${channel} selected in the simulation.`};}
 if(/repeater|near me/.test(q))return {intent:'query_repeaters',text:`Showing synthetic Midwest test fixtures near ${h.lat.toFixed(4)}, ${h.lon.toFixed(4)}. These are not operational repeater listings.`};
 if(/^(hi|hello|hey|how are you|how.s your day)[!?.\s]*$/.test(q))return {intent:'chat',text:'Hey! I’m ready for a little radio testing. How’s your day going?'};
 if(/battery/.test(q))return {intent:'query_status',text:`Simulated battery is ${h.battery}%. Radio state: ${h.state}.`};
 if(/transmit|ptt|send|emergency/.test(q))return {intent:'clarify',text:'No RF is transmitted by this web lab. Use the hardware panel to exercise simulated PTT events.'};
 return {intent:'clarify',text:'I’m a rule-based test double. Try “switch to channel 5”, “repeaters near me”, “battery status”, or “hello”. No radio setting was changed.'};
}
export const cities=[['Rockford','IL',42.2711,-89.094],['Beloit','WI',42.5083,-89.0318],['Madison','WI',43.0731,-89.4012],['Chicago','IL',41.8781,-87.6298],['Milwaukee','WI',43.0389,-87.9065],['Janesville','WI',42.6828,-89.0187],['Freeport','IL',42.2967,-89.6212],['Dubuque','IA',42.5006,-90.6646],['DeKalb','IL',41.9295,-88.7504],['Green Bay','WI',44.5133,-88.0133]];
export const fixtures=Array.from({length:50},(_,i)=>{const c=cities[i%10];return {id:`fixture-${i+1}`,callsign:`TEST-${String(i+1).padStart(3,'0')}`,city:c[0],state:c[1],latitude:Number(c[2])+(Math.floor(i/10)-2)*.03,longitude:Number(c[3])+(Math.floor(i/10)-2)*.04,rx: Number((444+i*.025).toFixed(3)),tx:Number((449+i*.025).toFixed(3)),offset:5,tone:i%3?'100.0 Hz':'CC 1',mode:['FM','DMR','P25'][i%3],power:25,height:30,status:'synthetic',synthetic:true};});
export function distance(lat:number,lon:number,a:number,b:number){const r=Math.PI/180;const x=Math.sin((a-lat)*r/2)**2+Math.cos(lat*r)*Math.cos(a*r)*Math.sin((b-lon)*r/2)**2;return 6371*2*Math.atan2(Math.sqrt(x),Math.sqrt(1-x));}
