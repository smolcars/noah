import { useCallback, useState } from "react";
import { useCameraPermission, useCodeScanner } from "react-native-vision-camera";
import { useAlert } from "~/contexts/AlertProvider";

type QRCodeScannerOptions = {
  onScan: (value: string) => void;
};

export const useQRCodeScanner = ({ onScan }: QRCodeScannerOptions) => {
  const [showCamera, setShowCamera] = useState(false);
  const { hasPermission, requestPermission } = useCameraPermission();
  const { showAlert } = useAlert();

  const handleCodeScanned = useCallback(
    (codes: { value?: string }[]) => {
      if (codes.length > 0 && codes[0].value) {
        const scannedValue = codes[0].value;
        onScan(scannedValue);
        setShowCamera(false);
      }
    },
    [onScan],
  );

  const codeScanner = useCodeScanner({
    codeTypes: ["qr", "ean-13"],
    onCodeScanned: handleCodeScanned,
  });

  const handleScanPress = async () => {
    if (!hasPermission) {
      const permissionGranted = await requestPermission();
      if (!permissionGranted) {
        showAlert({
          title: "Permission required",
          description: "Camera permission is required to scan QR codes.",
        });
        return;
      }
    }
    setShowCamera(true);
  };

  return {
    showCamera,
    setShowCamera,
    handleScanPress,
    codeScanner,
  };
};
