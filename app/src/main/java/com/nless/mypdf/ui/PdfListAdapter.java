package com.nless.mypdf.ui;


import com.nless.mypdf.R;
import com.nless.mypdf.data.PdfItem;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PdfListAdapter extends RecyclerView.Adapter<PdfListAdapter.ViewHolder> {

    private static final int TYPE_LIST = 0;
    private static final int TYPE_GRID = 1;
    private static final int TYPE_FOOTER = 2; // 新增：脚部提示类型

    private List<PdfItem> itemList;
    private OnItemInteractionListener listener;
    private boolean isGridView = false;
    private boolean showPath = true;
    private boolean isRecentMode = false; // 新增：控制是否追加脚部文字

    public interface OnItemInteractionListener {
        void onClick(PdfItem item);
        void onLongClick(View anchorView, PdfItem item);
    }

    public PdfListAdapter(List<PdfItem> itemList, OnItemInteractionListener listener) {
        this.itemList = itemList;
        this.listener = listener;
    }

    public void setGridView(boolean isGridView) {
        this.isGridView = isGridView;
        notifyDataSetChanged();
    }

    public void setShowPath(boolean showPath) {
        this.showPath = showPath;
        notifyDataSetChanged();
    }

    // 新增：动态开启最近查看专属的脚部提示
    public void setRecentMode(boolean isRecentMode) {
        this.isRecentMode = isRecentMode;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        // 如果是最近查看模式，列表总数需要 + 1 用于放置底部固定文字
        return isRecentMode ? itemList.size() + 1 : itemList.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (isRecentMode && position == itemList.size()) {
            return TYPE_FOOTER;
        }
        return isGridView ? TYPE_GRID : TYPE_LIST;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_FOOTER) {
            // 动态构建底部的固定提示文字布局
            TextView tvFooter = new TextView(parent.getContext());
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tvFooter.setLayoutParams(lp);
            tvFooter.setGravity(android.view.Gravity.CENTER);
            tvFooter.setPadding(0, 45, 0, 45);
            tvFooter.setText("— 只显示最近查看的10个文件 —");
            tvFooter.setTextColor(android.graphics.Color.GRAY);
            tvFooter.setTextSize(13);
            return new ViewHolder(tvFooter);
        }

        int layoutId = (viewType == 1) ? R.layout.item_file_grid : R.layout.item_file_list;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // 如果当前是脚部提示行，不需要绑定常规数据，直接跳过
        if (getItemViewType(position) == TYPE_FOOTER) {
            return;
        }

        PdfItem item = itemList.get(position);

        if (isGridView) {
            holder.tvGridName.setText(item.name);
            if (item.isFolder) {
                holder.tvGridTime.setVisibility(View.GONE);
            } else {
                holder.tvGridTime.setVisibility(View.VISIBLE);
                holder.tvGridTime.setText(item.time);
            }
        } else {
            holder.tvFileName.setText(item.name);
            holder.tvFileTime.setText(item.time);

            if (showPath && item.path != null && !item.path.isEmpty()) {
                holder.tvFilePath.setVisibility(View.VISIBLE);
                if (item.isFolder) {
                    holder.tvFilePath.setText("文件夹：" + item.path);
                } else {
                    holder.tvFilePath.setText("PDF文件：" + item.path);
                }
            } else {
                holder.tvFilePath.setVisibility(View.GONE);
            }

            if (item.isFolder) {
                holder.ivIcon.setImageResource(R.drawable.ic_folder);
                holder.tvFileTime.setVisibility(View.GONE);
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_file_pdf);
                holder.tvFileTime.setVisibility(View.VISIBLE);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (holder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                listener.onClick(item);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (holder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                listener.onLongClick(v, item);
            }
            return true;
        });
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFilePath, tvFileName, tvFileTime;
        ImageView ivIcon;
        TextView tvGridName, tvGridTime;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFilePath = itemView.findViewById(R.id.tv_file_path);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvFileTime = itemView.findViewById(R.id.tv_file_time);
            ivIcon = itemView.findViewById(R.id.iv_file_icon);
            tvGridName = itemView.findViewById(R.id.tv_grid_file_name);
            tvGridTime = itemView.findViewById(R.id.tv_grid_file_time);
        }
    }
}