import type {Metadata} from 'next';
import './globals.css';
import {Nav,Footer} from '../components/Shell';
export const metadata:Metadata={title:'RumiTX — Intelligence off the grid',description:'Radio control, local intelligence, and a virtual DOOGEE S80 test lab. Built for communication beyond the network.',metadataBase:new URL('https://rumitx-field-lab.realdvs1.chatgpt.site'),openGraph:{title:'RumiTX — Intelligence off the grid',description:'Explore the S80. Test the signal. Build what comes next.',images:['/og.svg']},icons:{icon:'/favicon.svg'}};
export default function Layout({children}:{children:React.ReactNode}){return <html lang='en'><body><Nav/>{children}<Footer/></body></html>}
