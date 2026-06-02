import { useCallback, useState } from "react";
import { useCameraPermission } from "react-native-vision-camera";
import { useBarcodeScannerOutput } from "react-native-vision-camera-barcode-scanner";
import type { Barcode, TargetBarcodeFormat } from "react-native-vision-camera-barcode-scanner";
import { useAlert } from "~/contexts/AlertProvider";
import logger from "~/lib/log";

const log = logger("useQRCodeScanner");

type QRCodeScannerOptions = {
  onScan: (value: string) => void;
};

const QR_SCANNER_BARCODE_FORMATS: TargetBarcodeFormat[] = ["qr-code", "ean-13"];

export const useQRCodeScanner = ({ onScan }: QRCodeScannerOptions) => {
  const [showCamera, setShowCamera] = useState(false);
  const { hasPermission, requestPermission } = useCameraPermission();
  const { showAlert } = useAlert();

  const handleBarcodeScanned = useCallback(
    (barcodes: Barcode[]) => {
      const scannedBarcode = barcodes.find((barcode) => barcode.rawValue || barcode.displayValue);
      const scannedValue = scannedBarcode?.rawValue ?? scannedBarcode?.displayValue;
      if (!scannedValue) {
        return;
      }

      onScan(scannedValue);
      setShowCamera(false);
    },
    [onScan],
  );

  const scannerOutput = useBarcodeScannerOutput({
    barcodeFormats: QR_SCANNER_BARCODE_FORMATS,
    onBarcodeScanned: handleBarcodeScanned,
    onError: (error) => {
      log.e("Failed to scan barcode", [error]);
    },
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
    scannerOutput,
  };
};
