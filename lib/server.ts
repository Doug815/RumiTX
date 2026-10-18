import {env} from 'cloudflare:workers';
import {getChatGPTUser} from '../app/chatgpt-auth';
export async function identity(){const u=await getChatGPTUser();const admins=((env as any).ADMIN_EMAILS||'').split(',').map((x:string)=>x.trim().toLowerCase());return u?{...u,admin:admins.includes(u.email.toLowerCase())}:null;}
export const db=()=> (env as any).DB as D1Database;
export async function save(kind:string,owner:string,body:any,id=crypto.randomUUID()){await db().prepare('INSERT INTO records (id,kind,owner,body,created) VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body').bind(id,kind,owner,JSON.stringify(body),Date.now()).run();return id;}
export async function list(kind:string,owner?:string){const sql=owner?'SELECT * FROM records WHERE kind=? AND owner=? ORDER BY created DESC LIMIT 200':'SELECT * FROM records WHERE kind=? ORDER BY created DESC LIMIT 200';const rows=await db().prepare(sql).bind(...(owner?[kind,owner]:[kind])).all();return rows.results.map((r:any)=>({id:r.id,created:r.created,owner:r.owner,...JSON.parse(r.body)}));}
