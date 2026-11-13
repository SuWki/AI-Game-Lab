/** 通用键值定义列表（详情展示）。 */
export function DefinitionList({ items }: { items: Array<[string, React.ReactNode]> }) {
  return (
    <dl style={{ display: "grid", gridTemplateColumns: "140px 1fr", rowGap: 8, columnGap: 12, margin: 0 }}>
      {items.map(([k, v], i) => (
        <div key={i} style={{ display: "contents" }}>
          <dt style={{ color: "#6b7280", fontSize: 13 }}>{k}</dt>
          <dd style={{ margin: 0, fontSize: 14, wordBreak: "break-word" }}>{v}</dd>
        </div>
      ))}
    </dl>
  );
}
