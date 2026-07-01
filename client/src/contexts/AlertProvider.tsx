import React, { createContext, useContext, useState, ReactNode } from "react";
import { NativeNoahAlertDialog } from "../components/ui/NativeNoahAlertDialog";

type AlertOptions = {
  title: string;
  description: string;
  onOk?: () => void;
};

type AlertContextType = {
  showAlert: (options: AlertOptions) => void;
};

const AlertContext = createContext<AlertContextType | undefined>(undefined);

export const useAlert = () => {
  const context = useContext(AlertContext);
  if (!context) {
    throw new Error("useAlert must be used within an AlertProvider");
  }
  return context;
};

export const AlertProvider = ({ children }: { children: ReactNode }) => {
  const [alertState, setAlertState] = useState<AlertOptions | null>(null);

  const showAlert = (options: AlertOptions) => {
    setAlertState(options);
  };

  const handleClose = () => {
    if (alertState?.onOk) {
      alertState.onOk();
    }
    setAlertState(null);
  };

  return (
    <AlertContext.Provider value={{ showAlert }}>
      {children}
      {alertState && (
        <NativeNoahAlertDialog
          open
          title={alertState.title}
          description={alertState.description}
          onConfirm={handleClose}
          onOpenChange={(open) => {
            if (!open) {
              setAlertState(null);
            }
          }}
        />
      )}
    </AlertContext.Provider>
  );
};
