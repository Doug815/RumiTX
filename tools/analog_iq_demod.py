#!/usr/bin/env python3
"""Offline, filtered complex-IQ FM verification. Requires numpy and scipy.
Capture separately with rtl_sdr. No ADB commands or transmission.
Unlike a peak in an arbitrary WAV, a tone tracking receiver carrier offset
must not be attributed to a transmitter without an offset comparison.
"""
import argparse,json,wave
from pathlib import Path
import numpy as np
from scipy.signal import resample_poly


def demodulate(path, sample_rate, center_hz, channel_hz, output_rate=24000):
    if sample_rate % output_rate or abs(center_hz-channel_hz) >= sample_rate/2:
        raise ValueError('integer decimation and channel inside sampled bandwidth required')
    raw=np.fromfile(path,np.uint8)
    if len(raw)%2: raise ValueError('truncated CU8 IQ pair')
    iq=raw.reshape(-1,2).astype(np.float32)-127.5
    z=iq[:,0]+1j*iq[:,1]
    z*=np.exp(2j*np.pi*(center_hz-channel_hz)*np.arange(len(z))/sample_rate).astype(np.complex64)
    # High stop-band rejection before decimation avoids aliasing receiver spurs.
    z=resample_poly(z,1,sample_rate//output_rate,window=('kaiser',12))
    return np.angle(z[1:]*z[:-1].conj())*output_rate/(2*np.pi)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('iq',type=Path);p.add_argument('--sample-rate',type=int,default=1008000)
    p.add_argument('--center-hz',type=int,required=True);p.add_argument('--channel-hz',type=int,required=True)
    p.add_argument('--start',type=float,required=True,help='confirmed active TX window, seconds')
    p.add_argument('--duration',type=float,default=4);p.add_argument('--reference-deviation-hz',type=float,default=2500)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.start<0 or a.duration<=0 or a.reference_deviation_hz<=0:p.error('invalid span or reference')
    y=demodulate(a.iq,a.sample_rate,a.center_hz,a.channel_hz);fs=24000
    span=y[round(a.start*fs):round((a.start+a.duration)*fs)]
    if len(span)<fs:p.error('need at least one second inside capture')
    win=np.hanning(len(span));amplitude=abs(np.fft.rfft((span-span.mean())*win))*2/win.sum();freq=np.fft.rfftfreq(len(span),1/fs)
    peaks={}
    for target in [100,961,1609,1680]:
        ids=np.flatnonzero(abs(freq-target)<=5);i=ids[np.argmax(amplitude[ids])]
        peaks[str(target)]={'peak_hz':float(freq[i]),'deviation_hz':float(amplitude[i]),'db_relative_reference':float(20*np.log10(max(amplitude[i],1e-12)/a.reference_deviation_hz))}
    report={'source':str(a.iq),'start_s':a.start,'duration_s':len(span)/fs,'carrier_offset_hz':float(span.mean()),'reference_deviation_hz':a.reference_deviation_hz,'tones':peaks,'limitation':'dB relative to explicit FM deviation; receiver noise and acoustic room noise included. A spectral line alone does not locate a hardware fault.'}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n')
    # Fixed scale, DC removed, never amplify a silent carrier to full scale.
    pcm=np.clip((y-y.mean())/a.reference_deviation_hz*32767,-32768,32767).astype('<i2')
    with wave.open(str(a.output),'wb') as out:out.setparams((1,2,fs,0,'NONE','not compressed'));out.writeframes(pcm.tobytes())
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
