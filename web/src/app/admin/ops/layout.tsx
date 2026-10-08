import { OperationsAccess } from "@/components/operations-shared";
import "./operations.css";

export default function OperationsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <OperationsAccess>{children}</OperationsAccess>;
}
