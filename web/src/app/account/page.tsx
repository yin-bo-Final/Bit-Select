"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Button, Empty, Table, Tag } from "antd";
import { ArrowUpRight, Wallet } from "@phosphor-icons/react";
import { api, date, money, errorText } from "@/lib/api";
import type { WalletEntry } from "@/lib/types";
import { ErrorState, LoadingState, LoginGate } from "@/components/common";
import { useSession } from "@/components/providers";
import { AddressBook } from "@/components/address-book";

const ledgerLabels: Record<string, string> = {
  CREDIT: "管理员分配",
  ADMIN_CREDIT: "管理员分配",
  PAYMENT: "订单付款",
  PAY: "订单付款",
  REFUND: "订单退款",
};
export default function AccountPage() {
  return (
    <LoginGate>
      <Account />
    </LoginGate>
  );
}
function Account() {
  const { user } = useSession();
  const [wallet, setWallet] = useState<{
    balanceCents: number;
    ledger: WalletEntry[];
  } | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    setError("");
    try {
      setWallet(await api("/wallet"));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  return (
    <div className="account-page">
      <div className="page-heading">
        <h1>{user?.nickname || user?.username}，你好。</h1>
        <p>你的账户、余额和每一笔消费。</p>
      </div>
      <section className="account-overview">
        <div className="wallet-panel">
          <Wallet size={27} />
          <span>可用余额</span>
          <strong>{money(wallet?.balanceCents ?? user?.balanceCents)}</strong>
          <p>平台余额仅用于本演示商城，联系管理员即可分配。</p>
        </div>
        <div className="account-links">
          <Link href="/orders">
            <span>查看我的订单</span>
            <ArrowUpRight size={24} />
          </Link>
          <Link href="/assistant">
            <span>管理导购记忆</span>
            <ArrowUpRight size={24} />
          </Link>
          {user?.role === "ADMIN" && (
            <Link href="/admin">
              <span>进入管理后台</span>
              <ArrowUpRight size={24} />
            </Link>
          )}
          <div className="account-id">
            账户 ID：{user?.id}
            <Tag>{user?.role === "ADMIN" ? "管理员" : "普通用户"}</Tag>
          </div>
        </div>
      </section>
      <AddressBook />
      <section className="ledger-section">
        <div className="heading-with-action">
          <h2>余额明细</h2>
          <Button onClick={load}>刷新余额</Button>
        </div>
        {error ? (
          <ErrorState error={error} retry={load} />
        ) : !wallet ? (
          <LoadingState />
        ) : (
          <Table<WalletEntry>
            rowKey="id"
            dataSource={wallet.ledger}
            pagination={{ pageSize: 10, hideOnSinglePage: true }}
            locale={{ emptyText: <Empty description="还没有余额记录" /> }}
            scroll={{ x: 650 }}
            columns={[
              { title: "时间", dataIndex: "createdAt", render: date },
              {
                title: "类型",
                dataIndex: "type",
                render: (type) => ledgerLabels[type] || type,
              },
              {
                title: "金额",
                dataIndex: "amountCents",
                align: "right",
                render: (value) => (
                  <strong className={value > 0 ? "credit-amount" : ""}>
                    {value > 0 ? "+" : ""}
                    {money(value)}
                  </strong>
                ),
              },
              {
                title: "变更后余额",
                dataIndex: "balanceAfterCents",
                align: "right",
                render: money,
              },
              {
                title: "关联记录",
                dataIndex: "referenceId",
                render: (value) => value || "无",
              },
            ]}
          />
        )}
      </section>
    </div>
  );
}
