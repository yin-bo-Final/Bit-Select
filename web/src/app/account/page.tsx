"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Button, Empty, Table, Tag } from "antd";
import {
  ArrowUpRight,
  ArrowClockwise,
  Wallet,
  Package,
  Brain,
  SlidersHorizontal,
  Receipt,
  UserCircle,
} from "@phosphor-icons/react";
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
    <div className="account-page commerce-page">
      <div className="page-heading commerce-account-heading">
        <div className="commerce-avatar">
          <UserCircle size={36} weight="duotone" />
        </div>
        <div>
          <h1>{user?.nickname || user?.username}，你好。</h1>
          <p>你的账户、余额和每一笔消费。</p>
        </div>
      </div>
      <section className="account-overview">
        <div className="wallet-panel">
          <div className="commerce-wallet-label">
            <Wallet size={23} />
            <span>可用余额</span>
            <span className="commerce-currency">CNY</span>
          </div>
          <strong>{money(wallet?.balanceCents ?? user?.balanceCents)}</strong>
          <div className="commerce-wallet-meta">
            <span>比特严选钱包</span>
            <span>账户 {user?.id}</span>
          </div>
          <p>平台余额仅用于本演示商城，联系管理员即可分配。</p>
        </div>
        <div className="account-links">
          <Link href="/orders">
            <span className="commerce-link-icon">
              <Package size={23} />
            </span>
            <span>
              <strong>查看我的订单</strong>
              <small>付款、配送与售后进度</small>
            </span>
            <ArrowUpRight size={20} />
          </Link>
          <Link href="/assistant">
            <span className="commerce-link-icon">
              <Brain size={23} />
            </span>
            <span>
              <strong>管理导购记忆</strong>
              <small>让下一次选择更了解你</small>
            </span>
            <ArrowUpRight size={20} />
          </Link>
          {user?.role === "ADMIN" && (
            <Link href="/admin">
              <span className="commerce-link-icon">
                <SlidersHorizontal size={23} />
              </span>
              <span>
                <strong>进入管理后台</strong>
                <small>商品、用户与交易管理</small>
              </span>
              <ArrowUpRight size={20} />
            </Link>
          )}
          <div className="account-id">
            <span>
              账户 ID：<span className="commerce-mono">{user?.id}</span>
            </span>
            <Tag>{user?.role === "ADMIN" ? "管理员" : "普通用户"}</Tag>
          </div>
        </div>
      </section>
      <AddressBook />
      <section className="ledger-section commerce-section">
        <div className="heading-with-action">
          <div className="commerce-section-title">
            <Receipt size={23} />
            <div>
              <h2>余额明细</h2>
              <p>每笔收支，都清楚记录。</p>
            </div>
          </div>
          <Button icon={<ArrowClockwise size={17} />} onClick={load}>
            刷新余额
          </Button>
        </div>
        {error ? (
          <ErrorState error={error} retry={load} />
        ) : !wallet ? (
          <LoadingState />
        ) : (
          <Table<WalletEntry>
            className="commerce-table"
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
                  <strong
                    className={`commerce-mono ${value > 0 ? "credit-amount" : ""}`}
                  >
                    {value > 0 ? "+" : ""}
                    {money(value)}
                  </strong>
                ),
              },
              {
                title: "变更后余额",
                dataIndex: "balanceAfterCents",
                align: "right",
                render: (value) => (
                  <span className="commerce-mono">{money(value)}</span>
                ),
              },
              {
                title: "关联记录",
                dataIndex: "referenceId",
                render: (value) => (
                  <span className="commerce-reference">{value || "无"}</span>
                ),
              },
            ]}
          />
        )}
      </section>
    </div>
  );
}
