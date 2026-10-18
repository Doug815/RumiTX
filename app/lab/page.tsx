import {requireChatGPTUser} from '../chatgpt-auth';
import {identity} from '../../lib/server';
import Lab from '../../components/Lab';
export const dynamic='force-dynamic';
export default async function Page(){await requireChatGPTUser('/lab');const u=await identity();return <Lab admin={!!u?.admin} name={u?.displayName.split('@')[0]||'Operator'}/>}
