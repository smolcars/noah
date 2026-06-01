import { useCallback, useState } from "react";
import {
  isScannedCode,
  useCameraPermission,
  useObjectOutput,
} from "react-native-vision-camera";
import type { ScannedObject, ScannedObjectType } from "react-native-vision-camera";
import { useAlert } from "~/contexts/AlertProvider";

type QRCodeScannerOptions = {
  onScan: (value: string) => void;
};

const QR_SCANNER_OBJECT_TYPES: ScannedObjectType[] = ["qr", "ean-13"];

export const useQRCodeScanner = ({ onScan }: QRCodeScannerOptions) => {
  const [showCamera, setShowCamera] = useState(false);
  const { hasPermission, requestPermission } = useCameraPermission();
  const { showAlert } = useAlert();

  const handleObjectsScanned = useCallback(
    (objects: ScannedObject[]) => {
      const scannedCode = objects.find(isScannedCode);
      if (!scannedCode?.value) {
        return;
      }

      onScan(scannedCode.value);
      setShowCamera(false);
    },
    [onScan],
  );

  const objectOutput = useObjectOutput({
    types: QR_SCANNER_OBJECT_TYPES,
    onObjectsScanned: handleObjectsScanned,
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
    objectOutput,
  };
};
