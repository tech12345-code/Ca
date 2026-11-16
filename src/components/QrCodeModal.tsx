import React, { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import jsQR from 'jsqr';
import { Camera, CheckCircle2, ExternalLink, QrCode, Upload, X, AlertTriangle } from 'lucide-react';
import { ParsedUpiPayload, parseUpiPaymentUri } from '../lib/splitEngine';

interface QrCodeModalProps {
  mode: 'DISPLAY' | 'SCAN';
  upiUri?: string;
  receiverName?: string;
  receiverUpiId?: string;
  amount?: number;
  currencySymbol?: string;
  note?: string;
  transactionRef?: string;
  onClose: () => void;
  onScanVerified?: (payload: ParsedUpiPayload, rawUri: string) => void;
  onLaunchUpiApp?: (appName: string) => void;
}

const UPI_APPS = [
  { id: 'gpay', name: 'Google Pay' },
  { id: 'phonepe', name: 'PhonePe' },
  { id: 'paytm', name: 'Paytm' },
  { id: 'bhim', name: 'BHIM UPI' },
];

export const QrCodeModal: React.FC<QrCodeModalProps> = ({
  mode: initialMode,
  upiUri = '',
  receiverName = '',
  receiverUpiId = '',
  amount = 0,
  currencySymbol = '₹',
  note = '',
  transactionRef = '',
  onClose,
  onScanVerified,
  onLaunchUpiApp,
}) => {
  const [activeMode, setActiveMode] = useState<'DISPLAY' | 'SCAN'>(initialMode);
  const [qrDataUrl, setQrDataUrl] = useState<string>('');
  const [qrError, setQrError] = useState<string | null>(null);

  // Scanner state
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [cameraActive, setCameraActive] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [scannedResult, setScannedResult] = useState<{
    raw: string;
    parsed: ParsedUpiPayload;
  } | null>(null);

  useEffect(() => {
    if (activeMode !== 'DISPLAY' || !upiUri) {
      setQrDataUrl('');
      return;
    }
    let cancelled = false;
    QRCode.toDataURL(upiUri, {
      width: 260,
      margin: 2,
      color: { dark: '#0B0D0F', light: '#FFFFFF' },
    })
      .then((url) => {
        if (!cancelled) {
          setQrDataUrl(url);
          setQrError(null);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setQrError(err instanceof Error ? err.message : 'Unable to generate QR code.');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [activeMode, upiUri]);

  // Camera scanner loop
  useEffect(() => {
    if (activeMode !== 'SCAN' || !cameraActive) return;

    let stream: MediaStream | null = null;
    let animId = 0;
    let stopped = false;

    const startCamera = async () => {
      try {
        setScanError(null);
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
        });
        if (stopped || !videoRef.current) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        videoRef.current.srcObject = stream;
        await videoRef.current.play();

        const tick = () => {
          if (stopped || !videoRef.current || !canvasRef.current) return;
          const video = videoRef.current;
          if (video.readyState === video.HAVE_ENOUGH_DATA) {
            const canvas = canvasRef.current;
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            const ctx = canvas.getContext('2d');
            if (ctx) {
              ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
              const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
              const decoded = jsQR(imgData.data, imgData.width, imgData.height);
              if (decoded?.data) {
                const parsed = parseUpiPaymentUri(decoded.data);
                setScannedResult({ raw: decoded.data, parsed });
                setCameraActive(false);
                if (parsed.valid && onScanVerified) {
                  onScanVerified(parsed, decoded.data);
                }
                return;
              }
            }
          }
          animId = requestAnimationFrame(tick);
        };
        animId = requestAnimationFrame(tick);
      } catch (err) {
        setScanError(
          err instanceof Error
            ? `Camera unavailable (${err.message}). Upload a QR image or paste UPI link below.`
            : 'Camera permission denied. Upload a QR image below.'
        );
        setCameraActive(false);
      }
    };

    startCamera();

    return () => {
      stopped = true;
      cancelAnimationFrame(animId);
      if (stream) stream.getTracks().forEach((t) => t.stop());
    };
  }, [activeMode, cameraActive, onScanVerified]);

  const handleQrImageUpload = (file: File) => {
    setScanError(null);
    const reader = new FileReader();
    reader.onload = () => {
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement('canvas');
        canvas.width = img.width;
        canvas.height = img.height;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        ctx.drawImage(img, 0, 0);
        const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const decoded = jsQR(imgData.data, imgData.width, imgData.height);
        if (!decoded?.data) {
          setScanError('No valid QR code found in the selected image.');
          return;
        }
        const parsed = parseUpiPaymentUri(decoded.data);
        setScannedResult({ raw: decoded.data, parsed });
        if (parsed.valid && onScanVerified) {
          onScanVerified(parsed, decoded.data);
        }
      };
      img.src = String(reader.result || '');
    };
    reader.readAsDataURL(file);
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 overflow-y-auto">
      <div className="w-full max-w-lg bg-[#121519] border border-white/15 rounded-2xl p-6 space-y-5 my-8">
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <QrCode className="w-5 h-5 text-[#C6FF33]" />
            <h2 className="text-lg font-semibold text-[#F3F5F7]">
              {activeMode === 'DISPLAY' ? 'Dynamic Settlement QR Code' : 'Scan UPI Payment QR'}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close modal"
            className="min-h-[40px] min-w-[40px] rounded-xl flex items-center justify-center text-[#9CA3AF] hover:text-white hover:bg-white/5 cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Mode Switcher */}
        <div className="grid grid-cols-2 gap-2 p-1 rounded-xl bg-[#0B0D0F] border border-white/10">
          <button
            type="button"
            disabled={!upiUri}
            onClick={() => {
              setCameraActive(false);
              setActiveMode('DISPLAY');
            }}
            className={`min-h-[40px] rounded-lg text-xs font-semibold transition-colors cursor-pointer disabled:opacity-40 ${
              activeMode === 'DISPLAY'
                ? 'bg-[#C6FF33] text-[#0B0D0F]'
                : 'text-[#9CA3AF] hover:text-white'
            }`}
          >
            Show Payment QR
          </button>
          <button
            type="button"
            onClick={() => setActiveMode('SCAN')}
            className={`min-h-[40px] rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
              activeMode === 'SCAN'
                ? 'bg-[#C6FF33] text-[#0B0D0F]'
                : 'text-[#9CA3AF] hover:text-white'
            }`}
          >
            Scan QR Code
          </button>
        </div>

        {activeMode === 'DISPLAY' ? (
          <div className="space-y-4">
            <div className="bg-[#0B0D0F] border border-white/10 rounded-xl p-4 flex flex-col items-center text-center space-y-3">
              {qrDataUrl ? (
                <div className="p-3 bg-white rounded-2xl">
                  <img src={qrDataUrl} alt="UPI Settlement QR Code" className="w-56 h-56" />
                </div>
              ) : (
                <div className="w-56 h-56 rounded-xl bg-[#181C22] flex items-center justify-center text-xs text-[#9CA3AF]">
                  {qrError || 'Receiver must configure a valid UPI ID to generate QR.'}
                </div>
              )}

              <div className="space-y-1">
                <p className="text-sm font-semibold text-[#F3F5F7]">
                  Pay {receiverName} ({currencySymbol}
                  {amount.toFixed(2)})
                </p>
                <p className="text-xs font-mono-num text-[#C6FF33]">{receiverUpiId || 'No UPI ID set'}</p>
                {note && <p className="text-xs text-[#9CA3AF]">{note}</p>}
                {transactionRef && (
                  <p className="text-[11px] font-mono-num text-[#6B7280]">Ref: {transactionRef}</p>
                )}
              </div>
            </div>

            {onLaunchUpiApp && upiUri && (
              <div className="space-y-2">
                <p className="text-xs text-[#9CA3AF]">
                  Choose a UPI app to initiate payment. Scanning or opening a UPI app does not auto-confirm payment; submit your UTR and confirm after paying.
                </p>
                <div className="grid grid-cols-2 gap-2">
                  {UPI_APPS.map((app) => (
                    <a
                      key={app.id}
                      href={upiUri}
                      onClick={() => onLaunchUpiApp(app.name)}
                      className="min-h-[42px] px-3 py-2 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] hover:border-[#C6FF33]/60 inline-flex items-center justify-between cursor-pointer"
                    >
                      <span>{app.name}</span>
                      <ExternalLink className="w-3.5 h-3.5 text-[#C6FF33]" />
                    </a>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="space-y-4">
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) handleQrImageUpload(file);
              }}
            />

            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                onClick={() => setCameraActive((prev) => !prev)}
                className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold inline-flex items-center justify-center gap-2 hover:bg-[#d4ff66] cursor-pointer"
              >
                <Camera className="w-4 h-4" />
                <span>{cameraActive ? 'Stop Camera' : 'Open Camera Scanner'}</span>
              </button>
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="min-h-[44px] px-4 py-2.5 rounded-xl bg-[#181C22] border border-white/10 text-xs font-medium text-[#F3F5F7] inline-flex items-center justify-center gap-2 hover:bg-[#20252d] cursor-pointer"
              >
                <Upload className="w-4 h-4 text-[#C6FF33]" />
                <span>Upload QR Image</span>
              </button>
            </div>

            {cameraActive && (
              <div className="relative rounded-xl overflow-hidden bg-black border border-[#C6FF33]/40 aspect-video flex items-center justify-center">
                <video ref={videoRef} playsInline muted className="w-full h-full object-cover" />
                <canvas ref={canvasRef} className="hidden" />
              </div>
            )}

            {scanError && (
              <div className="p-3 rounded-xl bg-[#EF4444]/10 border border-[#EF4444]/30 text-xs text-[#FCA5A5] flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{scanError}</span>
              </div>
            )}

            {scannedResult && (
              <div
                className={`p-4 rounded-xl border space-y-2.5 ${
                  scannedResult.parsed.valid
                    ? 'bg-[#2CE59B]/10 border-[#2CE59B]/40'
                    : 'bg-[#EF4444]/10 border-[#EF4444]/40'
                }`}
              >
                {scannedResult.parsed.valid ? (
                  <>
                    <div className="flex items-center gap-2 text-xs font-semibold text-[#2CE59B]">
                      <CheckCircle2 className="w-4 h-4" />
                      <span>Verified UPI Payment QR Decoded</span>
                    </div>
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div>
                        <p className="text-[#9CA3AF]">Receiver Name</p>
                        <p className="font-medium text-[#F3F5F7]">
                          {scannedResult.parsed.payeeName || 'Not specified'}
                        </p>
                      </div>
                      <div>
                        <p className="text-[#9CA3AF]">Receiver UPI ID</p>
                        <p className="font-mono-num text-[#C6FF33]">
                          {scannedResult.parsed.upiId}
                        </p>
                      </div>
                      <div>
                        <p className="text-[#9CA3AF]">Amount</p>
                        <p className="font-mono-num font-semibold text-[#F3F5F7]">
                          {scannedResult.parsed.amount > 0
                            ? `${currencySymbol}${scannedResult.parsed.amount.toFixed(2)}`
                            : 'Open amount'}
                        </p>
                      </div>
                      <div>
                        <p className="text-[#9CA3AF]">Reference</p>
                        <p className="font-mono-num text-[#F3F5F7]">
                          {scannedResult.parsed.transactionRef || 'None'}
                        </p>
                      </div>
                    </div>
                    <div className="pt-2 flex items-center gap-2">
                      <a
                        href={scannedResult.raw}
                        onClick={() => onLaunchUpiApp?.('Scanned UPI App')}
                        className="flex-1 min-h-[42px] px-4 py-2 rounded-xl bg-[#C6FF33] text-[#0B0D0F] text-xs font-semibold inline-flex items-center justify-center gap-2 hover:bg-[#d4ff66]"
                      >
                        <ExternalLink className="w-4 h-4" />
                        <span>Open in UPI App</span>
                      </a>
                    </div>
                  </>
                ) : (
                  <p className="text-xs text-[#FCA5A5]">{scannedResult.parsed.error}</p>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
